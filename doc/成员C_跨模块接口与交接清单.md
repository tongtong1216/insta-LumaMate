# LightPilot 成员 C：跨模块接口与交接清单

## 1. 目的

本文件用于统一成员 A、B、C、D 之间的字段、数据来源和责任边界。

成员 C 当前可以在没有真机和 API Key 的情况下完成：

```text
UserIntent
→ VisionMetrics
→ SceneSemantic
→ PolicyProposal
→ SafetyDecision
```

成员 C 不直接调用 Insta360 SDK，也不直接调用阿里云百炼。C 只使用 A、D 提供的结构化数据，并输出给 B 和 A 使用的策略结果。

## 2. 当前马上可以做的工作

### 2.1 先冻结数据契约

需要先确认以下对象的字段名称、类型和来源：

| 对象 | 提供方 | 使用方 | 作用 |
|---|---|---|---|
| `CameraState` | A | C、B | 当前相机状态 |
| `CameraCapabilities` | A | C、B | 当前模式下的合法参数 |
| `VisionMetrics` | C | B、D、C | 本地画面指标 |
| `UserIntent` | B/D/C | C、B | 用户拍摄偏好 |
| `SceneSemantic` | D | C、B | 云端模型返回的场景语义 |
| `PolicyProposal` | C | B、A | 受约束的参数建议 |
| `SafetyDecision` | C | B、A | 是否允许继续执行 |
| `ExecutionResult` | A | B、C、D | 实际执行和回读结果 |

### 2.2 先用 Mock 数据开发

在 A 没有完成真机 SDK 之前，C 可以使用：

- Mock `CameraState`
- Mock `CameraCapabilities`
- Mock 预览图片
- Mock `VisionMetrics`
- Mock `SceneSemantic`
- Mock `UserIntent`

所有 Mock 数据必须带来源标识：

```text
input_source = mock
execution_mode = mock
```

不能把 Mock 结果展示成真实相机成功。

### 2.3 先写策略测试

优先完成这些纯逻辑测试：

1. 同一画面，“优先主体”和“优先亮部”产生不同建议。
2. 目标参数不在相机合法列表时拒绝。
3. 相机 busy、录制中或状态未知时拒绝。
4. 旧帧、旧意图、旧能力版本使提案失效。
5. 重复 `commandId` 不重复执行。
6. 模型超时或语义不可用时安全 `HOLD`。
7. Auto 模式不生成快门/ISO 的 Manual 参数建议。
8. 白平衡建议不会逐帧自动切换。

## 3. 给成员 A 的文档和数据

你需要给 A 的不是完整算法代码，而是“相机适配层契约”。可以把本文件第 3 节发给 A，并请 A 按第 4 节提供能力快照。

### 3.1 需要 A 遵守的交接原则

1. C 不直接调用 Insta360 SDK。
2. A 将 SDK 的具体类型转换成统一 DTO。
3. A 提供当前真实状态，而不是 UI 上显示的缓存值。
4. 所有目标值必须来自 SDK 返回的合法值列表或合法范围。
5. 写入后必须提供最新回读值。
6. 模式切换、重连、能力变化后，旧提案必须失效。
7. `setter` 超时不能直接判定为“没有执行”，应标记为未知状态。

### 3.2 C 需要的 `CameraState`

```kotlin
data class CameraState(
    val connectionEpoch: String,
    val mode: String?,
    val exposureProgram: ExposureProgram,
    val currentEv: Double?,
    val currentIso: Int?,
    val currentShutterSpeed: ShutterSpeed?,
    val currentWhiteBalance: Int?,
    val isWorking: Boolean?,
    val isPreRecording: Boolean?,
    val isBusy: Boolean,
    val recordingState: RecordingState,
    val frameSource: FrameSource,
    val capabilityRevision: Long
)

data class ShutterSpeed(
    val numerator: Double,
    val denominator: Double
)
```

建议枚举：

```kotlin
enum class ExposureProgram {
    AUTO,
    MANUAL,
    UNKNOWN
}

enum class RecordingState {
    IDLE,
    RECORDING,
    STARTING,
    STOPPING,
    UNKNOWN
}

enum class FrameSource {
    SDK_DECODED,
    SDK_RENDERED_PREVIEW,
    MANUAL_IMPORT,
    MOCK,
    UNKNOWN
}
```

说明：

- `currentEv`、`currentShutterSpeed`、`currentIso`、`currentWhiteBalance` 允许为 `null`。
- 不支持或无法确认时使用 `null`/`UNKNOWN`，不要用 `0` 冒充有效值。
- `isWorking = null` 表示 SDK 无法确认状态，策略层应按不安全处理。
- `connectionEpoch` 变化后，之前的提案和待执行命令全部失效。

### 3.3 C 需要的 `CameraCapabilities`

```kotlin
data class CameraCapabilities(
    val supportedEv: List<Double>,
    val supportedShutterSpeed: List<ShutterSpeed>,
    val supportedIso: List<Int>,
    val supportedWhiteBalance: List<Int>,
    val supportedExposurePrograms: List<ExposureProgram>,
    val supportParam: Set<String>,
    val capabilityRevision: Long,
    val capturedAtEpochMs: Long
)
```

参数名应与官方 SDK 的 `CameraParam` 保持一致：

```text
ISO
exposureBias
exposureShutterSpeed
whiteBalance
exposureProgram
```

如果当前 SDK Demo 使用的是常量名而不是上述字符串，A 应在适配层保留官方常量名，并在 DTO 中映射成统一字段。

当前规划不把测光模式作为已确认能力。只有 A 在实际 SDK Demo 中验证存在对应 API 后，才新增字段。

### 3.4 请 A 提供的能力快照

```json
{
  "mode": "VIDEO",
  "exposureProgram": "AUTO",
  "supportParam": [
    "exposureBias",
    "exposureProgram",
    "whiteBalance"
  ],
  "supportedEv": [],
  "currentEv": null,
  "supportedShutterSpeed": [],
  "currentShutterSpeed": null,
  "supportedIso": [],
  "currentIso": null,
  "supportedWhiteBalance": [],
  "currentWhiteBalance": null,
  "loadJson": "UNKNOWN",
  "syncAllParams": "UNKNOWN",
  "isWorking": null,
  "isPreRecording": null,
  "isBusy": false,
  "recordingState": "IDLE",
  "connectionEpoch": "session-001",
  "capabilityRevision": 1,
  "capturedAt": "2026-09-22T00:00:00Z"
}
```

上面的数组和值只是字段示例，不能当作 GO Ultra 的真实支持范围。

## 4. 给成员 D 的数据需求

### 4.1 不需要 D 给你的 API Key

不需要，也不应该让 D 把 API Key 发给你。

阿里云官方要求不要把长期有效 API Key 配置在移动应用、客户端代码、不可信环境中，也不要提交到代码仓库或日志。API Key 应留在 D 负责的后端环境变量或密钥管理系统中。

D 只需要给你：

- 已验证的视觉模型名称；
- 后端接口地址或本地 Mock 地址；
- `SceneSemantic` 的字段定义；
- 正常响应样例；
- 超时、限流、鉴权失败、JSON 格式错误等失败样例；
- 模型不可用时后端返回的统一状态。

API Key 只由 D 的后端使用，Android 端和 C 的策略模块都不接触 Key。

### 4.2 C 需要的 `SceneSemantic`

```kotlin
data class SceneSemantic(
    val available: Boolean,
    val scene: String?,
    val subjectType: String?,
    val brightRegionType: String?,
    val coloredLight: Boolean?,
    val uncertainty: Float?,
    val reason: String?,
    val sourceFrameId: String?,
    val receivedAtEpochMs: Long,
    val expiresAtEpochMs: Long?,
    val intentRevision: Long? = null,
    val uncertaintyNotes: List<String> = emptyList(),
    val analysisStatus: String? = null
)
```

这是 C 内部归一化对象，不是 D v1 的原始 JSON。D v1 原始响应使用
`status`、`frame_id`、`intent_revision` 和 `uncertainty[]`，由
`V1SceneSemanticDataSource` 转换后才能进入 C。

C 内部对象示例：

```json
{
  "available": true,
  "scene": "indoor_backlight",
  "subjectType": "person",
  "brightRegionType": "window",
  "coloredLight": false,
  "uncertainty": 0.0,
  "reason": "人物位于较亮窗户前方，主体和背景存在明显亮度冲突。",
  "sourceFrameId": "frame-1042",
  "receivedAtEpochMs": 1790035200000,
  "expiresAtEpochMs": 1790035204000,
  "intentRevision": 3,
  "uncertaintyNotes": [],
  "analysisStatus": "ok"
}
```

### 4.3 语义服务失败时的统一响应

D v1 的失败响应使用：

```json
{
  "frame_id": 152,
  "intent_revision": 3,
  "status": "unavailable",
  "uncertainty": ["timeout"],
  "reason": "模型请求超时"
}
```

C 适配后的内部对象为：

```json
{
  "available": false,
  "scene": null,
  "subjectType": null,
  "brightRegionType": null,
  "coloredLight": null,
  "uncertainty": 1.0,
  "reason": "模型请求超时",
  "sourceFrameId": "152",
  "receivedAtEpochMs": 1790035200000,
  "expiresAtEpochMs": null,
  "intentRevision": 3,
  "uncertaintyNotes": ["timeout"],
  "analysisStatus": "unavailable"
}
```

推荐的 `reason`：

```text
timeout
model_unavailable
rate_limited
authentication_failed
invalid_model_response
connection_failed
backend_busy
internal_error
```

模型不可用时，C 可以继续运行本地亮度指标，但不能使用旧语义继续执行新的相机命令。
`reason` 和 `uncertaintyNotes` 只能用于展示、日志和失效原因，不能通过关键词
匹配来决定 EV、快门、ISO 或白平衡动作。

## 5. `UserIntent`：C 需要和 B/D 统一的对象

自然语言不应直接传给 `PolicyEngine`。B/D 可以把自然语言转换成受控的权重对象：

```kotlin
data class UserIntent(
    val revision: Long,
    val subjectDetail: Float,
    val highlightDetail: Float,
    val motionClarity: Float,
    val lowNoise: Float,
    val colorNeutrality: Float,
    val atmospherePreservation: Float,
    val exposureStability: Float,
    val sourceText: String?,
    val createdAtEpochMs: Long
)
```

所有权重范围统一为 `0.0..1.0`。

示例：

```json
{
  "revision": 3,
  "subjectDetail": 1.0,
  "highlightDetail": 0.3,
  "motionClarity": 0.1,
  "lowNoise": 0.2,
  "colorNeutrality": 0.4,
  "atmospherePreservation": 0.2,
  "exposureStability": 0.7,
  "sourceText": "优先把人脸拍清楚，天空亮一点没关系",
  "createdAt": 1790035200000
}
```

`createdAt` 的具体数值只作为示例，实际实现建议使用 Android 单调时钟或统一的服务端时间协议，不能直接用不同机器的时间戳相减判断端到端延迟。

## 6. C 输出给 B 和 A 的对象

### 6.1 `PolicyProposal`

```kotlin
data class PolicyProposal(
    val proposalId: String,
    val intentRevision: Long,
    val frameId: String,
    val action: PolicyAction,
    val parameter: ParameterTarget?,
    val reason: String,
    val risk: RiskLevel,
    val cost: Float,
    val validUntilEpochMs: Long,
    val connectionEpoch: String,
    val capabilityRevision: Long,
    val inputSource: InputSource,
    val createdAtEpochMs: Long,
    val diagnostics: PolicyDiagnostics?,
    val executionMode: ExecutionMode
)

data class PolicyDiagnostics(
    val subjectRisk: Float?,
    val highlightRisk: Float?,
    val darkRisk: Float?,
    val upScore: Float,
    val downScore: Float,
    val scoreMargin: Float,
    val semanticUncertainty: Float?,
    val semanticUsed: Boolean
)
```

建议动作：

```kotlin
enum class PolicyAction {
    HOLD,
    EV_ONE_STEP_UP,
    EV_ONE_STEP_DOWN,
    SET_SHUTTER,
    SET_ISO,
    SET_WHITE_BALANCE
}
```

阶段一只允许：

```text
HOLD
EV_ONE_STEP_UP
EV_ONE_STEP_DOWN
```

阶段二、阶段三的动作必须等 A 验证对应参数支持后再启用。

### 6.2 `SafetyDecision`

```kotlin
data class SafetyDecision(
    val allowed: Boolean,
    val reason: SafetyReason,
    val message: String,
    val checkedProposalId: String?,
    val checkedAtEpochMs: Long
)
```

拒绝原因至少包括：

```kotlin
enum class SafetyReason {
    ALLOWED,
    NO_ACTION,
    STALE_FRAME,
    STALE_INTENT,
    STALE_CAPABILITY,
    CAMERA_BUSY,
    RECORDING,
    UNKNOWN_CAMERA_STATE,
    ILLEGAL_TARGET,
    DUPLICATE_COMMAND,
    MODEL_UNAVAILABLE,
    USER_LOCKED,
    CONNECTION_CHANGED,
    UNSUPPORTED_PARAMETER,
    EXPIRED_PROPOSAL,
    MISSING_COMMAND_ID
}
```

`MISSING_COMMAND_ID` 表示真实参数执行请求没有唯一命令标识。所有非 `HOLD` 的执行请求都必须有 `commandId`，用于防止重复写入。

### 6.3 C 不负责执行

C 输出建议和安全判定，A 负责最终调用 SDK：

```text
C: PolicyProposal + SafetyDecision
→ B: 展示建议并等待用户确认
→ A: 执行合法、明确、已确认的动作
→ A: 返回 ExecutionResult
→ B/C: 展示和验证 before/target/readback
```

共享执行结果统一定义为：

```kotlin
enum class ExecutionStatus {
    SUCCESS,
    FAILED,
    TIMEOUT,
    UNKNOWN
}

enum class ReadbackStatus {
    MATCHED,
    MISMATCHED,
    UNAVAILABLE,
    UNKNOWN
}

data class ExecutionResult(
    val commandId: String,
    val proposalId: String,
    val executionStatus: ExecutionStatus,
    val beforeValue: ParameterTarget?,
    val targetValue: ParameterTarget?,
    val readbackValue: ParameterTarget?,
    val readbackStatus: ReadbackStatus,
    val errorCode: String?,
    val connectionEpoch: String,
    val capabilityRevision: Long,
    val inputSource: InputSource,
    val executionMode: ExecutionMode
)
```

A 必须把写入结果和回读结果分开返回。setter 超时或回读失败时，
不得直接返回 `SUCCESS`。

## 7. `VisionMetrics` 最小字段

```kotlin
data class VisionMetrics(
    val frameId: String,
    val source: FrameSource,
    val roiVersion: String,
    val subjectBrightness: Float?,
    val backgroundBrightness: Float?,
    val highlightRatio: Float?,
    val darkRatio: Float?,
    val motionScore: Float?,
    val capturedAtEpochMs: Long,
    val expiresAtEpochMs: Long?
)
```

字段范围建议：

- 亮度和比例统一为 `0.0..1.0`；
- 不可计算时使用 `null`；
- 不把均值亮度直接描述成“画质”；
- `frameId` 必须能与 A 的真实帧或 Mock 帧对应；
- 旧帧不能继续生成新的有效提案。

预览帧交接统一使用：

```kotlin
interface PreviewFrameDataSource {
    fun readLatestFrame(): GrayFrame?
}
```

A 负责把 SDK 预览帧转换为 C 可分析的标准帧；C 不接触 SDK 的帧对象。

## 7.1 当前代码中的边界接口

当前 `:core` 已提供以下契约接口：

```text
CameraDataSource          A -> C
PreviewFrameDataSource    A -> C
CameraCommandExecutor     B -> A
SceneSemanticDataSource   D -> C
```

这些接口是模块边界，不代表 A、B、D 的真实实现已经完成。

### 7.2 D v1 场景分析接口

D 提供的候选协议文件为：

```text
doc/API_CONTRACT_V1_CANDIDATE.md
```

C 已增加对应的协议 DTO 和适配器：

```text
core/src/main/kotlin/com/lightpilot/core/contract/v1/SceneAnalysisContract.kt
```

主要类型：

```kotlin
AnalyzeSceneRequest
AnalyzeSceneMetrics
AnalyzeSceneResponse
SceneAnalysisClient
V1SceneSemanticDataSource
```

`SceneSemanticDataSource` 的调用签名已经调整为：

```kotlin
fun readSemantic(
    request: AnalyzeSceneRequest,
    nowEpochMs: Long
): SceneSemantic
```

原因是 D v1 请求除了 C 的指标，还必须携带：

```text
frame_id
intent_revision
intent
image_base64
```

`AnalyzeSceneRequest.fromMetrics()` 会把 C 内部的字符串帧号转换为 D 要求的
非负 Int64；不能转换的帧号会立即拒绝，不能悄悄生成另一个帧号。

D 返回后，`V1SceneSemanticDataSource` 会检查帧号和意图版本绑定：

```text
response.frame_id == request.frame_id
response.intent_revision == request.intent_revision
status == ok
```

不满足时转换为不可用语义，C 只能输出 `HOLD`。`reason` 只给 B 展示或日志
使用，不能用关键词决定策略。D 的 `uncertainty[]` 会原样放入
`SceneSemantic.uncertaintyNotes`；非空时数值不确定度按 1.0 保守处理。

## 8. 成员 D 的最小交付清单

D 不需要把密钥交给 C，但需要提供：

1. 后端接口路径，例如 `/analyze-scene`；
2. 请求字段；
3. 响应字段；
4. 已验证的视觉模型名称；
5. Base URL 是否只在后端使用；
6. 正常响应样例；
7. `401/403/429/timeout/invalid_json` 样例；
8. 超时和有效期约定；
9. 是否对图片做缩放或压缩；
10. 后端返回的 `frame_id` 和 `intent_revision` 原样绑定规则；
11. 脱敏后的请求日志格式。

## 9. 现在的工作顺序

建议按以下顺序推进：

### 第一步：把本文件发给 A、B、D

要求他们只反馈字段和能力，不先修改核心实现。

### 第二步：A 提供真实能力快照

最少先确认：

```text
exposureBias / EV
exposureProgram
shutter
ISO
whiteBalance
```

每项都要有支持状态、当前值、合法值和最新回读结果。

### 第三步：D 提供脱敏的 `SceneSemantic`

不需要 API Key，只需要正常和失败 JSON。

### 第四步：B/D 确认 `UserIntent`

确认自然语言如何映射到权重，至少先支持：

```text
优先主体
优先亮部
整体平衡
曝光稳定
```

### 第五步：C 实现纯策略模块

先不接真实 SDK：

```text
Mock CameraState
Mock CameraCapabilities
Mock VisionMetrics
Mock SceneSemantic
Mock UserIntent
→ PolicyEngine
→ PolicyCoordinator
→ TemporalController
→ SafetyGuard
```

`PolicyCoordinator` 是 C 侧单帧策略评估入口，负责把候选提案、
连续帧确认和安全预检串起来。默认需要连续 3 帧确认同一个动作，
动作冷却时间为 3 秒；意图版本、相机连接版本或能力版本变化时，
必须清空等待状态。

阶段二、三在真实 SDK 能力未确认前，可以使用 Mock 能力列表生成：

```text
SET_SHUTTER
SET_ISO
SET_WHITE_BALANCE
```

但这些提案必须同时满足：

```text
executionMode = MOCK
SafetyDecision.allowed = false
```

它们只能用于展示策略理由和测试接口，不能提交给 A 执行。

### 第六步：冻结共享协议

最终将确认后的对象放入：

```text
shared/contract.md
```

跨模块字段修改必须由提出者和至少一名受影响成员确认。

## 10. 安全结论

```text
API Key：只在 D 的后端环境变量/密钥管理中
Android：只调用 D 的后端接口
C：只处理结构化 SceneSemantic，不接触 Key
A：只处理相机 SDK，不接触 Key
Git/日志：不出现完整 Key、Authorization 或完整 Base64
```

## 11. 联调准入条件

### C 可以继续独立开发的部分

```text
UserIntent -> VisionMetrics -> SceneSemantic
           -> PolicyProposal -> SafetyDecision
```

Mock 阶段不需要等待 A、B、D，也不需要 API Key。

### 接入 A 前必须拿到

```text
[ ] CameraState 真实快照
[ ] CameraCapabilities 真实快照
[ ] 真实预览帧的 frameId 和时间戳
[ ] EV 是否可读写及 supportedEv
[ ] 写入后的 ExecutionResult 和 readback
[ ] connectionEpoch/capabilityRevision 更新规则
```

### 接入 B 前必须确认

```text
[ ] 自然语言如何生成 UserIntent
[ ] UserIntent.revision 递增规则
[ ] userLocked 的来源和解除规则
[ ] B 只展示/确认，不修改 PolicyProposal
[ ] commandId 生成和去重规则
[ ] Mock 与 REAL 的页面状态文案
[ ] 阶段二/三 Mock 建议明确显示“仅模拟，不执行真机”
```

### 接入 D 前必须拿到

```text
[ ] 后端 URL 和请求方法
[ ] POST /api/v1/analyze-scene 的 snake_case 请求字段
[ ] frame_id、intent_revision、图片/帧数据和压缩规则
[ ] status=ok/mock/unavailable 的正常响应
[ ] timeout、401/403、429、invalid_model_response 响应
[ ] frame_id/intent_revision 原样绑定和过期丢弃规则
[ ] API Key 不进入 Android
```

只有以上清单由对应成员确认后，才进入真实联调；否则继续使用 Mock，
不能把缺省值当作真实相机或真实模型结果。

官方参考：

- [获取与配置阿里云百炼 API Key](https://help.aliyun.com/zh/model-studio/get-api-key)
- [百炼文本生成与多模态消息](https://help.aliyun.com/zh/model-studio/text-generation)
- [Insta360 GO Android Camera API](https://insta360develop.github.io/Insta360-Developer_Docs/ch/go/android/camera-api/)
