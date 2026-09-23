# LightPilot 三阶段用户意图规划实现

## 当前完成度

| 阶段 | 意图 | 策略输出 | 当前执行级别 |
|---|---|---|---|
| 阶段一：自动曝光偏好 | 主体、高光、平衡、稳定 | `HOLD`、相邻一档 EV 升/降 | 已实现真实执行契约，等待 A 接入 GO Ultra 真机适配器 |
| 阶段二：运动清晰度与噪点 | 动作清晰、低噪点、亮度优先、运动平衡 | `HOLD`、相邻快门或 ISO | `MOCK/planning_only`，禁止真实执行 |
| 阶段三：色彩与氛围 | 颜色还原、自然肤色、保留氛围、保留彩色光、颜色稳定 | `HOLD`、能力列表中的白平衡 | `MOCK/planning_only`，禁止真实执行 |

rc3 已具备百炼意图解析、严格 JSON Mapper、三个独立权重、字段级语义降级、三个阶段
候选生成、统一连续帧确认和单动作仲裁。阶段二、三已有合法值选择、解释和测试。
它们没有伪装成真机闭环；A 尚未提供真实能力快照和专用执行器，因此生成的提案固定为：

```text
executionMode = MOCK
inputSource = MOCK
```

`AdvancedSafetyGuard` 对非 `HOLD` 提案返回 `MOCK_EXECUTION`，不会调用阶段一的 EV
执行器，也不会调用任何 Insta360 SDK setter。

## 代码位置

- `AdvancedModels.kt`：阶段二/三意图、指标、能力、状态和提案。
- `MultiStageIntent.kt`：百炼响应的严格 Mapper、独立权重、确认门槛和 revision 管理。
- `MultiStagePolicy.kt`：候选评分、三/五帧门控、约束和单动作仲裁。
- `AdvancedPolicyEngine.kt`：快门、ISO、白平衡候选策略。
- `AdvancedTemporalController.kt`：普通 3 帧、高稳定 5 帧确认和冷却。
- `MainActivity.kt`：本地 Mock 演示入口。
- `FieldScopedUncertaintyTest.kt`、`MultiStageIntentTest.kt`、
  `MultiStagePolicyTest.kt`：字段级降级、严格解析、融合和冲突测试。

`AdvancedIntentMapper` 只保留在旧 Mock 单阶段演示中，不进入 rc3 确认和执行路径。rc3
真实路径调用 `/api/v1/parse-intent`，解析失败时要求用户手动选择。

## 阶段二规则

阶段二只在 Mock 状态为 `MANUAL`、语义可用、状态已知并且 Mock 能力列表完整时生成候选：

- `MOTION_CLARITY`：运动指标达到阈值时，选择当前快门在合法列表中的相邻更快值。
- `LOW_NOISE`：选择当前 ISO 在合法列表中的相邻更低值。
- `BRIGHTNESS_PRIORITY`：暗部比例达到阈值时，选择相邻更高 ISO。
- `MOTION_BALANCED`：强运动优先提高一档快门；运动不强但明显偏暗时提高一档 ISO。
- 当前参数不在能力列表、到达边界、不是 Manual 或运动指标缺失时 `HOLD`。

初版一次只建议一个参数，不生成快门和 ISO 的复合写入。

## 阶段三规则

- `COLOR_ACCURACY`：选择 Mock 能力列表中最接近本地估计色温的值。
- `NATURAL_SKIN`：只有语义确认人物或多人主体时，选择最接近配置目标的合法值。
- `ATMOSPHERE_PRESERVATION`：保持当前白平衡，避免主动中和现场冷暖色。
- `COLORED_LIGHT_PRESERVATION`：彩色光场景保持当前值。
- `COLOR_STABILITY`：在 A 未确认白平衡锁定语义前返回 `HOLD`。

## 与 D 后端的关系

D 的 API 已升级为 `1.0.0-rc3`。`/api/v1/parse-intent` 只返回权重和固定意图枚举；
`/api/v1/analyze-scene` 增加 `uncertainty_details`，仍不返回快门、ISO、白平衡或相机动作。
三个阶段共用 D 的低频 `SceneSemantic`，参数方向和目标值始终由 C 从本地指标及相机能力
列表产生。

字段级降级规则：

- 主体类型/ROI 不确定不阻断全局运动策略或高光曝光策略；
- `NATURAL_SKIN` 依赖 `subject_type`；
- 彩色光/氛围策略依赖 `colored_light` 和 `scene`；
- `blocking`、`affects=all`、模型失败和必要本地指标缺失继续安全 HOLD。

统一仲裁使用 `candidate_score = stage_weight × urgency`。每轮最多选择一个动作；前两名
差值 `<0.10` 时要求用户选择。最高权重阶段被 Mock、能力、模式或语义依赖阻断时，不会
静默执行低权重动作。

## 本地验证

D 先使用三份真实代表图片完成真实模型验收，具体命令和判定标准见
[`lightpilot-backend/docs/D_THREE_STAGE_TEST_GUIDE.md`](../lightpilot-backend/docs/D_THREE_STAGE_TEST_GUIDE.md)。

D 通过后，再验证 Android 本地策略：

```bash
cd /Users/kugua/insta360-autu_adjust
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

安装到模拟器后先在首页使用“rc3 多阶段意图”：

1. 启动本机后端，模拟器通过 `http://10.0.2.2:8000` 调用百炼解析；
2. 检查三个独立权重和枚举，补全激活但 priority 为 null 的阶段；
3. 点击确认后检查 `intent_revision` 才递增。

随后向下滚动到“阶段二/三策略规划”验证 Mock 候选：

1. 普通模式连续提交 3 帧，高稳定模式连续提交 5 帧；
2. 查看动作、目标合法值、原因码、`MOCK` 标识；
3. 点击“验证执行安全拦截”，预期显示 `MOCK_EXECUTION`。

## 升级为真实执行前的门槛

A 必须针对 GO Ultra 当前固件和拍摄模式提供：

- Auto/Manual 切换能力及切换后的能力重读结果；
- 快门、ISO、白平衡的可读、可写状态和真实合法值列表；
- 录制中写入限制、忙态、超时、断连和写后回读行为；
- 白平衡参数是否包含 Auto/锁定语义。

随后还需要单独设计阶段二/三执行器、参数专用 SafetyGuard、写后回读和超时恢复测试。
在这些工作完成前，不得把 Mock 提案改成 `REAL`。
