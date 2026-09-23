# LightPilot 大模型效果团队联调说明书

## 1. 测试目标

本说明书供 A、B、C、D 共同验证以下四层结果：

1. 百炼 `qwen3.8-flash` 能把用户文本解析为三个阶段的结构化意图；
2. 真实图片和视频代表帧能够返回合法、可追踪的 `SceneSemantic`；
3. Android 能正确使用 `frame_id`、`intent_revision` 和字段级不确定性；
4. C 的策略、安全检查和 A 的相机执行不会把 Mock、旧结果或失败结果当作可执行命令。

必须分开记录“模型效果通过”“Android 数据链通过”和“真实相机执行通过”。看到
`/health` 正常、HTTP 200 或模型返回一段中文，都不能单独判定联调成功。

当前仓库默认仍使用 `FakeCameraAdapter`，阶段二和阶段三仍为 `MOCK/planning_only`。如果联调
分支尚未替换真实 `CameraAdapter`，相机执行部分只验证安全拦截，不得记录为真机执行通过。

## 2. 角色分工

| 成员 | 联调时负责的内容 |
|---|---|
| A | 提供真实相机帧、能力列表、当前状态、参数写入和写后回读 |
| B | 输入用户意图、展示模型结果、让用户确认意图和最终建议 |
| C | 计算本地指标、消费语义、生成候选、连续帧确认和安全仲裁 |
| D | 启动真实百炼后端、检查协议、模型语义、延迟、错误码和报告 |

D 不直接输出或执行 EV、快门、ISO、白平衡和 SDK 方法。`reason` 只用于展示与人工检查，
C 不得解析 `reason` 自然语言制定相机策略。

## 3. 测试前准备

### 3.1 后端配置

在仓库根目录打开终端：

```bash
cd lightpilot-backend
source .venv/bin/activate
```

本机 `lightpilot-backend/.env` 应配置：

```dotenv
LIGHTPILOT_MODE=bailian
BAILIAN_API_KEY=自己的密钥
BAILIAN_BASE_URL=对应工作空间的compatible-mode/v1地址
BAILIAN_MODEL_ID=qwen3.8-flash
BAILIAN_TIMEOUT_SECONDS=30
```

不要把密钥发送到群聊、截图、测试报告、APK 或 Git。修改 `.env` 后必须重启后端。

### 3.2 启动后端

```bash
cd /绝对路径/insta360-autu_adjust/lightpilot-backend
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

打开另一个终端：

```bash
curl -s http://127.0.0.1:8000/health
```

必须确认：

```json
{
  "status": "ok",
  "mode": "bailian",
  "model_configured": true,
  "model": "qwen3.8-flash"
}
```

这只证明配置已经加载，下一节的真实请求成功后才能证明模型已接入。

### 3.3 Android 网络连接

Android 官方模拟器使用：

```text
http://10.0.2.2:8000
```

USB Android 真机先执行：

```bash
adb devices -l
adb -s DEVICE_SERIAL reverse tcp:8000 tcp:8000
```

App 使用：

```text
http://127.0.0.1:8000
```

`adb devices -l` 中的真机必须为 `device`，不能是 `unauthorized` 或 `offline`。

## 4. 第一组测试：用户意图解析

执行：

```bash
cd lightpilot-backend

.venv/bin/python -m scripts.smoke \
  --kind intent \
  --intent "夜间跑步时拍清楚人物，同时保留霓虹灯颜色" \
  --expect-all-stages \
  --show-result \
  --repeat 3
```

通过标准：

- 三次均为 HTTP 200、`status=ok`、`passed=true`；
- 三个权重均为 `[0,1]` 数字，且不要求总和为 1；
- 权重 `>=0.50` 的阶段必须有合法 priority，或者明确要求用户补选；
- 输出只能使用固定枚举；
- 输出中不能出现 EV、ISO、快门值、白平衡值或 SDK 方法；
- 同一输入的权重可以轻微变化，最终以用户确认值为准。

建议另外测试以下文本：

| 测试文本 | 重点观察 |
|---|---|
| 优先拍清楚逆光人物，同时尽量保留天空 | 曝光阶段激活，通常为 `subject_detail` |
| 宁愿动作有一点模糊，也要人物更亮 | 应倾向 `subject_detail` 和 `brightness_priority`；当前 rc3 没有显式模糊容忍度，需人工确认 |
| 我要拍人物剪影并保留天空层次 | 当前 rc3 没有剪影枚举，只能近似为 `highlight_detail`，不能记录为完整剪影功能通过 |
| 动作要清楚，同时保留霓虹灯颜色 | 运动和色彩阶段应同时激活 |

如果模型不能唯一确定某个已激活阶段的 priority，正确结果是返回 ambiguity 并让用户手动选择，
不能猜测相机动作。

## 5. 第二组测试：三阶段真实图片

准备三张真实图片：

| 阶段 | 建议素材 |
|---|---|
| 阶段一 曝光 | 逆光人物、暗主体亮背景、人物加亮屏 |
| 阶段二 运动噪点 | 走动人物、跑步、夜间运动或低光动作 |
| 阶段三 色彩氛围 | 霓虹灯、舞台彩灯、暖色室内人物或混合色温 |

执行：

```bash
cd lightpilot-backend

.venv/bin/python -m scripts.three_stage_probe \
  --stage1-image "/绝对路径/逆光人物.jpg" \
  --stage2-image "/绝对路径/运动低光.jpg" \
  --stage3-image "/绝对路径/彩色灯光.jpg" \
  --repeat 3 \
  --expect-stage3-colored-light \
  --output "test-results/team-three-stage-report.json"
```

如果阶段三图片没有明显彩色光，删除 `--expect-stage3-colored-light`。

通过标准：

```text
overall_passed = true
contract_passed = true
stage_usable = true
required_field_stability = true
```

人工检查每次输出：

- `frame_id` 和 `intent_revision` 与请求一致；
- `scene`、`subject_type`、`bright_region_type` 和 `colored_light` 符合画面；
- `reason` 基于图片事实，没有把用户文字当作画面事实；
- 同一图片连续三次的必要字段稳定；
- warning 只阻断依赖字段的阶段；
- `blocking` 或 `affects=["all"]` 必须使相关策略 HOLD。

## 6. 第三组测试：真实视频抽帧

执行：

```bash
cd lightpilot-backend

.venv/bin/python -m scripts.video_probe \
  --video "/绝对路径/测试视频.mp4" \
  --samples 6 \
  --intent "优先冻结运动，同时保留现场灯光" \
  --exposure-priority balanced \
  --stability-preference normal \
  --intent-revision 1 \
  --output "test-results/team-video-report.json"
```

通过标准：

- `contract_passed=true`；
- 成功帧编号和 revision 正确；
- `stage_usable` 正确反映每个阶段能否使用；
- `field_stability` 记录字段变化；
- 模型失败的帧明确为 `unavailable`，不能冒充成功；
- 临时抽帧在脚本结束后被删除，报告中没有 API Key 或图片 Base64。

视频脚本只是均匀抽取代表帧调用 D，不等于 C 的连续 3/5 帧控制。C 的连续帧判断必须使用
本地高频指标和缓存语义，不能连续调用六次模型来代替。

## 7. 第四组测试：Android 完整数据链

按以下顺序操作并录屏或截图保存结果：

1. B 输入用户原文并调用 `/api/v1/parse-intent`；
2. 检查三个权重、priority 和 ambiguity；
3. 用户补全缺失项并点击确认，此时 `intent_revision` 才递增；
4. A 或 Android 预览提供当前真实代表帧；
5. C 计算本地指标并调用 `/api/v1/analyze-scene`；
6. Android 核对 `frame_id`、`intent_revision` 和 `status=ok`；
7. C 使用缓存语义与本地指标生成三个阶段候选；
8. 普通模式连续 3 帧、高稳定模式连续 5 帧后才出现稳定建议；
9. 仲裁器每轮最多选择一个动作；
10. 用户确认后才进入 SafetyGuard；
11. 真实适配器执行后展示执行前值、目标值和实际回读值。

界面至少应展示：

```text
用户确认后的结构化意图和 intent_revision
当前 frame_id
SceneSemantic 和字段级警告
三个阶段的权重、候选、阻断原因和连续帧进度
最终单动作建议及原因
执行前值、目标值、回读值
模型延迟和错误状态
```

## 8. 必测场景

### 8.1 逆光人物需要脸部更亮

预期：D 返回逆光场景、人物主体和天空或窗户亮区；C 根据人物 ROI、本地亮度和高光溢出
决定 EV 上调、冲突 HOLD 或保持。D 不直接返回 EV。

### 8.2 逆光剪影

当前 rc3 只能用 `highlight_detail` 近似表达。测试时应记录“背景高光语义是否正确”和“C 是否
倾向降低曝光”，不能把它记录为完整剪影模式通过；完整功能需要后续增加固定剪影意图和人物
暗度目标。

### 8.3 运动人物但优先脸部亮度

预期意图倾向 `subject_detail + brightness_priority`。当前 rc3 没有显式
`motion_blur_tolerance`，阶段二也仍为 planning only，因此本轮主要验证模型理解、候选展示和
安全阻断，不能记录为真实慢快门执行通过。

### 8.4 夜间运动加霓虹灯

预期运动和色彩阶段同时激活；彩色光保护可以约束白平衡建议；每轮仍只能选择一个动作。

### 8.5 主体遮挡

预期主体 ROI 或人物相关策略 HOLD，但不依赖主体类型的全局运动策略仍可继续评估。

## 9. 安全与故障测试

至少执行以下检查：

| 场景 | 正确结果 |
|---|---|
| 修改意图后旧模型结果返回 | revision 不一致，直接丢弃 |
| 模型 timeout、429、鉴权或连接失败 | `unavailable`，全部相关动作 HOLD |
| `status=mock` | 显示 Mock，不能执行相机命令 |
| `affects=["all"]` | 所有阶段 HOLD |
| 只有 `colored_light` 不确定 | 只阻断依赖彩色光的阶段三策略 |
| 相机 busy 或状态未知 | SafetyGuard 拒绝执行 |
| 建议超过 5 秒 | 过期并重新分析 |
| 重复 `command_id` | 拒绝重复执行 |
| Stage 2/3 为 planning only | 可以展示，不能调用真实 setter |

## 10. 结果记录模板

每名成员至少提交一条记录：

| 字段 | 填写内容 |
|---|---|
| 测试人/角色 | A、B、C 或 D |
| Git 提交 | 完整 commit SHA |
| 手机/相机/固件 | 实际型号和版本 |
| 后端模式/模型 | `bailian / qwen3.8-flash` |
| 测试素材 | 场景描述，不上传隐私原图 |
| 用户意图 | 实际输入文本 |
| intent_revision/frame_id | 实际编号 |
| 模型结果 | 状态、固定枚举、不确定性 |
| 模型延迟 | 毫秒 |
| C 最终建议 | 动作、原因码、是否 HOLD |
| 执行结果 | 未执行、Mock 拦截或真实 readback |
| 结论 | 通过、失败或受限通过 |
| 问题 | 复现步骤和脱敏日志 |

## 11. 最终通过标准

### 大模型效果通过

- `/parse-intent` 和 `/analyze-scene` 使用真实百炼且协议有效；
- 三类图片各连续三次满足阶段必要字段要求；
- 视频报告能够区分协议、阶段可用性和字段稳定性；
- 模型不输出或控制相机参数。

### Android 数据链通过

- 用户确认后才更新 revision；
- 旧 frame、旧 revision、Mock 和 unavailable 均被拒绝；
- 字段级不确定性只影响依赖它的阶段；
- 连续帧控制使用本地指标和缓存语义；
- 每轮最多一个动作。

### 真实相机执行通过

- 使用真实 `CameraAdapter` 和真实能力列表；
- 每个动作都经过用户确认和 SafetyGuard；
- 能展示 `before / target / readback`；
- 写入超时后状态未知，重新同步前不再执行；
- Stage 2/3 只有在 A 完成真实能力、执行器和回读验收后才能解除 `planning_only`。

只有三层分别达到各自标准，才可以写“项目完整闭环通过”。

## 12. 常见问题

| 现象 | 检查方法 |
|---|---|
| `/health` 正常但模型请求失败 | 健康检查不证明密钥可用，查看真实冒烟错误码 |
| `backend_is_mock` | 设置 `LIGHTPILOT_MODE=bailian` 并重启服务 |
| `authentication_failed` | 检查 Key、Base URL、工作空间和地域是否一致 |
| `timeout` | 检查网络、图片尺寸和 30 秒超时设置 |
| `invalid_model_response` | 保存错误码，不让 Android 猜测模型字段 |
| 手机无法访问电脑 | 检查 `adb reverse`、Base URL 和后端监听地址 |
| 输出语义正确但没有建议 | 查看阶段权重、连续帧进度、本地指标和 SafetyGuard |
| 有建议但不能执行 | 检查是否仍为 Mock/planning only，或相机状态是否可写 |

