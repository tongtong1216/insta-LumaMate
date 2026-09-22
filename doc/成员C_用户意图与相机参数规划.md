# LightPilot 成员 C：用户意图与相机参数规划

## 1. 文档目的

LightPilot 不是替代 GO Ultra 原有的自动曝光，而是在相机自动调参的基础上增加一层“用户意图”：

```text
相机自动调参
+ 用户更重视的内容
+ 当前画面中的风险
= 受约束、可解释、可回退的参数建议
```

用户可以用自然语言表达“我更想保留什么”，系统将其转换为受控的用户意图，再由本地策略决定是否建议修改相机参数。

大模型只负责提供场景语义和解释，不直接生成 SDK 方法名、具体参数值或相机控制命令。

## 2. 产品边界

### 2.1 当前版本的核心闭环

```text
真实预览/试拍
→ 用户自然语言意图
→ 本地画面指标
→ 场景语义
→ 受约束参数建议
→ 用户确认
→ 相机执行
→ 最新状态回读
→ 结果观察
```

### 2.2 参数能力的事实来源

GO Ultra 的触屏功能或产品手册支持某个参数，不代表当前 Android SDK 一定能读写该参数。

根据 Insta360 GO Android Camera API 官方文档，SDK 的 `CameraParam` 对象目前明确提供以下相机参数入口：

```text
ISO
exposureBias       EV
exposureShutterSpeed
whiteBalance
exposureProgram
```

这些参数可以通过统一的参数对象读取支持情况、读取当前值和写入目标值：

```text
getSupportParam()
getSupported()
fetchValue()
getValue()
setValue(value)
```

SDK 还提供 `loadJson()` 和 `syncAllParams()` 等参数初始化/同步能力。具体设备、拍摄模式和当前固件是否真的支持某个参数，仍必须由成员 A 在 GO Ultra 真机上验证。

所有策略都必须以成员 A 使用当前 Insta360 Android SDK 实际验证得到的能力为准：

- 当前拍摄模式
- Auto / Manual 曝光模式
- 参数是否可读
- 参数是否可写
- 当前值
- 合法值列表或合法范围
- 写入后的最新回读
- 忙态、断连、超时和状态未知处理

策略层不能硬编码 EV 步长、ISO 范围、快门范围或白平衡范围。

本规划暂不把“测光模式”“人脸优先测光”“Matrix 测光”视为已确认的 SDK 控制项。官方 Camera API 页面列出了上述五类参数，但没有在同一参数列表中确认测光模式的读写接口。

官方文档：

- [Insta360 GO Android Camera API](https://insta360develop.github.io/Insta360-Developer_Docs/ch/go/android/camera-api/)

## 3. 三阶段用户意图规划

## 阶段一：自动曝光偏好

这是 48 小时版本的 P0 范围，优先保证真实闭环。

### 3.1 用户意图

| 意图 | 含义 |
|---|---|
| `SUBJECT_DETAIL` | 优先看清人物、商品、宠物等主体细节 |
| `HIGHLIGHT_DETAIL` | 优先保留天空、屏幕、窗外景色、灯牌等亮部细节 |
| `BALANCED` | 主体与亮部之间保持整体平衡 |
| `EXPOSURE_STABILITY` | 不因轻微画面变化频繁忽明忽暗 |

### 3.2 典型冲突

| 场景 | 用户表达 | 策略倾向 |
|---|---|---|
| 逆光人像 | “请把人脸拍清楚，天空亮一点没关系” | 主体优先，可能建议提高曝光 |
| 逆光人像 | “脸暗一点没关系，我要保留窗外景色” | 亮部优先，可能建议降低曝光 |
| 人物与电脑屏幕 | “请优先看清屏幕上的字” | 保护亮部，避免屏幕过曝 |
| 人物与电脑屏幕 | “屏幕无所谓，人物表情最重要” | 主体优先，允许屏幕部分过亮 |
| 舞台人物与聚光灯 | “我要看清演员的脸” | 主体优先 |
| 舞台灯牌 | “我要保留灯牌的颜色和形状” | 亮部优先 |
| 日落剪影 | “我想拍剪影，人物暗一点没关系” | 氛围/亮部优先，避免强行提亮 |

### 3.3 需要相机支持的参数

读取：

```text
mode
exposureProgram
currentEv
supportedEv
isBusy
recordingState
connectionEpoch
capabilityRevision
```

写入：

```text
EV
```

可选扩展，只有 A 在实际 SDK 中确认后才能加入：

```text
MeteringMode
```

P0 动作：

```text
HOLD
EV_ONE_STEP_UP
EV_ONE_STEP_DOWN
```

这里的“一档”必须是 `supportedEv` 中当前值的相邻合法值，不是固定加减 1。

## 阶段二：运动清晰度与噪点

这一阶段开始涉及 `exposureProgram` 和 Manual 模式，主要解决快门、ISO 和运动之间的权衡。

### 4.1 用户意图

| 意图 | 含义 |
|---|---|
| `MOTION_CLARITY` | 优先冻结运动，宁愿画面暗一点或有噪点 |
| `LOW_NOISE` | 优先降低噪点，接受一定运动模糊 |
| `BRIGHTNESS_PRIORITY` | 优先保证整体亮度 |
| `MOTION_BALANCED` | 在动作清晰度、亮度和噪点之间折中 |

### 4.2 典型冲突

| 场景 | 用户表达 | 策略倾向 |
|---|---|---|
| 夜间宠物奔跑 | “不要拖影，动作要清楚” | 提高快门，必要时允许 ISO 上升 |
| 室内静止人物 | “不要那么多噪点” | 限制 ISO，允许快门较慢 |
| 骑行或滑板 | “优先动作清晰” | Manual 模式下优先较快快门 |
| 演唱会 | “人物不要糊，但画面也不要太脏” | 在快门和 ISO 之间受约束权衡 |

### 4.3 需要相机支持的参数

读取：

```text
exposureProgram
currentShutterSpeed
supportedShutterSpeed
currentIso
supportedIso
frameRate
isBusy
recordingState
```

写入：

```text
exposureProgram = MANUAL
exposureShutterSpeed
ISO
```

`exposureProgram` 本身也是 SDK 文档列出的参数，但 Auto 是否可以切换到 Manual、切换后哪些参数可写，必须以 GO Ultra 当前 SDK 和固件的实际结果为准。

模式从 Auto 切换到 Manual 后必须：

1. 重新读取能力列表；
2. 重新读取快门和 ISO 当前值；
3. 让旧的 `PolicyProposal` 失效；
4. 重新生成建议；
5. 经过用户确认后再执行。

建议动作：

```text
HOLD
SET_SHUTTER
SET_ISO
```

初版应优先修改一个主参数，避免一次大幅修改多个参数。
如果以后确实需要同时修改快门和 ISO，应新增并共同确认新的复合动作契约，
当前版本不把 `SET_SHUTTER_AND_ISO` 视为已冻结接口。

## 阶段三：色彩与氛围

这一阶段主要处理白平衡，不直接等同于曝光控制。

### 5.1 用户意图

| 意图 | 含义 |
|---|---|
| `COLOR_ACCURACY` | 尽量还原真实颜色 |
| `NATURAL_SKIN` | 优先保证人物肤色自然 |
| `ATMOSPHERE_PRESERVATION` | 保留现场的暖色、冷色或整体氛围 |
| `COLORED_LIGHT_PRESERVATION` | 保留舞台灯、霓虹灯等彩色光线 |
| `COLOR_STABILITY` | 避免录制过程中白平衡反复跳变 |

### 5.2 典型冲突

| 场景 | 用户表达 | 策略倾向 |
|---|---|---|
| 暖色室内灯 | “人物肤色自然一点” | 偏向中性白平衡 |
| 暖色室内灯 | “保留餐厅的温暖感觉” | 保留较高色温，不强行中和 |
| 舞台彩灯 | “保留现场灯光颜色” | 避免频繁自动改白平衡 |
| 混合光源 | “不要一会儿偏蓝、一会儿偏黄” | 场景稳定后锁定白平衡 |

### 5.3 需要相机支持的参数

读取：

```text
currentWhiteBalance
supportedWhiteBalance
currentFilter（暂不作为本阶段核心参数）
```

写入：

```text
whiteBalance
```

在 SDK 层，白平衡相关字段最终要映射到官方参数名 `whiteBalance`；白平衡值的具体类型、是否支持 Auto 以及是否存在独立的白平衡模式字段，需要成员 A 根据当前 SDK Demo 确认。如果 SDK 只提供一个 `whiteBalance` 参数，就不要在策略层假设存在独立的 Auto/Manual 白平衡开关。

白平衡不建议逐帧自动调整。推荐流程：

```text
检测场景稳定
→ 生成白平衡建议
→ 用户确认
→ 写入一次
→ 在一段时间内锁定
```

## 4. 成员 C 的职责

成员 C 不直接调用 Insta360 SDK，而是实现本地的视觉和策略模块：

```text
FrameAnalyzer
PolicyEngine
PolicyCoordinator
TemporalController
SafetyGuard
```

### 4.1 FrameAnalyzer

从预览帧或降采样帧中计算：

- 主体/ROI 亮度
- 背景亮度
- 暗部比例
- 亮部截断比例
- 可选：运动指标

输出 `VisionMetrics`。

### 4.2 PolicyEngine

输入：

- `UserIntent`
- `VisionMetrics`
- `SceneSemantic`
- `CameraState`
- `CameraCapabilities`

输出：

- 动作类型
- 合法目标参数
- 理由
- 风险
- 代价
- 提案有效期

策略必须优先保证：

1. 用户意图被体现；
2. 目标值来自相机实际合法列表；
3. 当前模式支持该参数；
4. 不因单一指标做过度推断；
5. 无法确定时 `HOLD`。

阶段二、三在 A 尚未确认真实 SDK 能力时，只生成带
`executionMode=MOCK` 的策略提案，用于验证用户意图到参数方向的映射。
这些提案不允许真实执行。目标值必须来自 Mock 能力列表，没有合法列表
就返回 `HOLD`。

意图优先级固定为：

```text
明显的主体/亮部曝光冲突
→ P0 EV 策略

运动清晰、低噪点或色彩意图明显高于曝光偏好
→ 阶段二/三 Mock 策略

多个高级意图接近
→ HOLD，不随意猜测
```

### 4.3 TemporalController

- 指标指数平滑；
- 连续多帧确认；
- 动作冷却；
- 防止单帧异常导致频繁切换；
- 场景变化或模式变化后重置相关状态。

默认需要连续 3 帧确认同一个动作，动作冷却时间为 3 秒。
`PolicyCoordinator` 负责调用 `TemporalController`，并在用户意图版本、
相机连接版本或能力版本变化时重置状态。

### 4.4 SafetyGuard

以下情况必须拒绝、暂停或让提案失效：

- 帧过期或帧 ID 不匹配；
- 用户意图已经变化；
- 相机忙、录制中或状态未知；
- 目标参数不在合法列表中；
- 用户手动锁定参数；
- `command_id` 重复；
- `connectionEpoch` 已变化；
- `capabilityRevision` 已变化；
- 模型超时、JSON 无效或语义不可用；
- setter 超时后实际状态未知。

## 5. 用户意图数据模型建议

自然语言不应直接进入执行层，建议先转换成受控结构：

```kotlin
data class UserIntent(
    val revision: Long,
    val subjectDetail: Float = 0f,
    val highlightDetail: Float = 0f,
    val motionClarity: Float = 0f,
    val lowNoise: Float = 0f,
    val colorNeutrality: Float = 0f,
    val atmospherePreservation: Float = 0f,
    val exposureStability: Float = 0f,
    val sourceText: String? = null
)
```

权重范围建议统一为 `0.0..1.0`。

示例：

```text
“优先看清人脸”
subjectDetail = 1.0
highlightDetail = 0.3
exposureStability = 0.7

“屏幕文字不要过曝”
highlightDetail = 1.0
subjectDetail = 0.5

“宁愿暗一点也不要拖影”
motionClarity = 1.0
lowNoise = 0.3

“保留夜晚的暖色氛围”
atmospherePreservation = 1.0
colorNeutrality = 0.2
```

## 6. 成员 A 需要提供的相机能力快照

是的，相机参数必须向成员 A 要。应要求 A 基于当前 GO Ultra 实际使用的 Android SDK / 官方 Demo 提供能力快照，而不是只给产品手册中的理论参数。

可以直接发送给 A：

```text
请基于当前 GO Ultra 实际使用的 Android SDK / 官方 Demo，
提供当前拍摄模式下以下参数的真实能力：

1. 当前模式和曝光模式
   - mode
   - exposureProgram: Auto / Manual
   - recordingState
   - isBusy

2. EV
   - 是否支持读取
   - 是否支持写入
   - 当前值
   - supportedEv 实际列表
   - 写入后能否通过最新 fetch/readback 确认

3. 参数初始化与状态
   - loadJson() 是否成功
   - syncAllParams() 是否成功
   - getSupportParam() 返回的支持参数
   - isWorking() 的实际语义和返回结果

4. 快门
   - 是否支持读取
   - 是否支持写入
   - 当前值
   - 合法值列表或范围
   - 是否只能在 Manual 模式使用

5. ISO
   - 是否支持读取
   - 是否支持写入
   - 当前值
   - 合法值列表或范围
   - 是否只能在 Manual 模式使用

6. 白平衡
   - 是否支持读取
   - 是否支持写入
   - 是否支持 Auto
   - 支持的色温范围或离散值

7. 异常状态
   - 不支持
   - 当前模式不允许
   - 相机忙
   - 连接断开
   - setter 成功但 readback 未改变
   - 超时后状态未知

8. 可选测光能力
   - 只有在当前 SDK Demo 中确实存在对应 API 时提供
   - 是否支持读取
   - 是否支持写入
   - 支持哪些模式
```

建议 A 最终提供类似以下结构的 JSON：

```json
{
  "mode": "VIDEO",
  "exposureProgram": "AUTO",
  "supportedEv": [],
  "currentEv": null,
  "supportedShutterSpeed": [],
  "currentShutter": null,
  "supportedIso": [],
  "currentIso": null,
  "supportedWhiteBalance": [],
  "currentWhiteBalance": null,
  "supportParam": [],
  "loadJson": "UNKNOWN",
  "syncAllParams": "UNKNOWN",
  "isWorking": false,
  "isBusy": false,
  "recordingState": "IDLE",
  "connectionEpoch": "session-001",
  "capabilityRevision": 1
}
```

上面的值只是字段示例，不能当作 GO Ultra 的真实 SDK 能力。

## 7. 团队分工边界

| 成员 | 负责内容 |
|---|---|
| A | 验证并封装相机 SDK 的真实读写能力，提供状态、能力列表、帧和执行结果 |
| B | 展示用户意图、策略理由、确认操作、执行状态和回读结果 |
| C | 视觉指标、用户意图到策略的映射、时序控制、安全检查和测试 |
| D | 自然语言/场景语义服务、协议、集成测试和交付验收 |

## 8. 推荐开发顺序

### 48 小时版本

优先完成：

```text
UserIntent
→ VisionMetrics
→ PolicyEngine
→ PolicyCoordinator
→ PolicyProposal
→ HOLD / EV_ONE_STEP_UP / EV_ONE_STEP_DOWN
→ TemporalController
→ SafetyGuard
→ 单元测试
```

### 后续扩展

```text
阶段二：快门/ISO 的运动与噪点权衡
阶段三：白平衡锁定与色彩氛围控制
```

如果当前 SDK 没有验证某项参数，策略必须返回 `HOLD` 或“当前模式不支持该意图”，不能伪造成功。

## 9. P0 验收标准

至少完成以下测试：

1. 同一画面下，“优先看清主体”和“优先保留亮部”产生不同建议或不同 HOLD 理由。
2. EV 目标值来自真实 `supportedEv`，不能使用硬编码步长。
3. 非法目标值不会调用 setter。
4. 旧帧、旧意图、旧能力版本会使提案失效。
5. 相机 busy 或状态未知时不会执行。
6. 相同 `command_id` 不会重复执行。
7. 模型不可用时仍能安全 `HOLD`。
8. 所有建议都能解释“为什么建议这个动作”。
9. `before`、`target`、`readback`、`restored_readback` 分开记录。
10. Mock 数据明确标记为 `input_source=mock`，不能伪装成真机成功。
11. 阶段二/三 Mock 提案明确标记 `executionMode=mock`，且
    `SafetyDecision.allowed=false`，不能提交给 A。
