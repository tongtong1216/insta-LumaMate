# 成员 C：PolicyEngine 输入输出数据类规范

## 1. 文档目的

本文档用于成员 B 在 Android UI 和应用协调层接入成员 C 的策略模块。

B 的目标是：

```text
按照本文件组装 C 所需的输入
→ 调用 C 的策略入口
→ 展示 C 返回的建议和安全状态
```

B 不需要修改 `PolicyEngine` 的策略逻辑，也不应复制或重写 C 的 EV
计算、合法值选择和安全检查。

当前核心代码位于：

```text
core/src/main/kotlin/com/lightpilot/core/
```

Android 模块通过以下依赖使用 C 模块：

```kotlin
implementation(project(":core"))
```

---

## 2. 推荐调用入口

虽然可以直接调用 `PolicyEngine.propose()`，但 Android UI 推荐使用
`PolicyCoordinator.evaluate()`，因为它会统一处理：

- `PolicyEngine` 候选策略；
- `TemporalController` 连续帧确认；
- `SafetyGuard` 安全预检；
- 用户意图版本变化；
- 相机连接版本变化；
- 相机能力版本变化。

推荐调用流程：

```kotlin
val cycleResult = policyCoordinator.evaluate(
    input = policyInput,
    commandId = commandId
)
```

返回：

```kotlin
data class PolicyCycleResult(
    val candidateProposal: PolicyProposal,
    val temporalDecision: TemporalDecision,
    val safetyDecision: SafetyDecision,
    val canRequestConfirmation: Boolean
)
```

字段含义：

| 字段 | 含义 |
|---|---|
| `candidateProposal` | C 当前计算出的候选策略 |
| `temporalDecision` | 连续帧确认和冷却状态 |
| `safetyDecision` | 当前是否通过安全检查 |
| `canRequestConfirmation` | UI 是否可以进入“等待用户确认”状态 |

UI 不建议自己重新实现 `PolicyCoordinator`、`TemporalController` 或
`SafetyGuard`。

### 2.1 依赖文件与“StateMachine”对应关系

当前 C 模块没有名为 `StateMachine.kt` 的单独类。Android UI 需要的状态
流转由以下三个类共同完成：

| 作用 | 当前实现 | B 的使用方式 |
|---|---|---|
| 策略协调入口 | `PolicyCoordinator` | 调用 `evaluate()` |
| 连续帧确认和冷却 | `TemporalController` | 不直接调用，读取 `TemporalDecision` |
| 参数安全检查 | `SafetyGuard` | 不直接重算，读取 `SafetyDecision` |

对应文件：

```text
core/src/main/kotlin/com/lightpilot/core/policy/PolicyEngine.kt
core/src/main/kotlin/com/lightpilot/core/policy/PolicyCoordinator.kt
core/src/main/kotlin/com/lightpilot/core/policy/TemporalController.kt
core/src/main/kotlin/com/lightpilot/core/policy/SafetyGuard.kt
core/src/main/kotlin/com/lightpilot/core/model/Models.kt
```

`PolicyCoordinator` 的公共入口：

```kotlin
class PolicyCoordinator(
    private val engine: PolicyEngine = PolicyEngine(),
    private val temporalController: TemporalController = TemporalController(),
    private val safetyGuard: SafetyGuard = SafetyGuard()
) {
    fun evaluate(
        input: PolicyInput,
        commandId: String? = null
    ): PolicyCycleResult

    fun reset()
}
```

`SafetyGuard` 的公共入口：

```kotlin
class SafetyGuard(
    private val maxFrameAgeMs: Long = 1_500L
) {
    fun evaluate(
        proposal: PolicyProposal,
        currentState: CameraState,
        capabilities: CameraCapabilities,
        currentMetrics: VisionMetrics,
        currentIntent: UserIntent,
        nowEpochMs: Long,
        commandId: String?,
        userLocked: Boolean = false
    ): SafetyDecision

    fun clearForNewConnection()
}
```

B 正常情况下只调用 `PolicyCoordinator.evaluate()`，不需要直接调用
`SafetyGuard.evaluate()`。只有 C 的单元测试或底层适配测试才需要直接调用
`SafetyGuard`。

因此可以把当前状态机理解为：

```text
PolicyCoordinator
  ├─ PolicyEngine：生成候选 PolicyProposal
  ├─ TemporalController：等待连续帧确认
  └─ SafetyGuard：执行最终安全预检
```

---

## 3. `PolicyEngine` 直接输入

如果测试或特殊场景需要直接调用策略引擎，入口是：

```kotlin
class PolicyEngine(
    private val config: PolicyConfig = PolicyConfig()
) {
    fun propose(input: PolicyInput): PolicyProposal
}
```

输入对象：

```kotlin
data class PolicyInput(
    val intent: UserIntent,
    val metrics: VisionMetrics,
    val semantic: SceneSemantic,
    val cameraState: CameraState,
    val capabilities: CameraCapabilities,
    val nowEpochMs: Long,
    val userLocked: Boolean = false,
    val inputSource: InputSource = InputSource.REAL,
    val executionMode: ExecutionMode = ExecutionMode.REAL
)
```

输入来源：

| 字段 | 提供方 | 说明 |
|---|---|---|
| `intent` | D 或 Mock 解析器，B 负责传递 | 用户拍摄偏好 |
| `metrics` | C 的 `FrameAnalyzer` | 当前帧的画面指标 |
| `semantic` | D 或 Mock 服务 | 场景语义 |
| `cameraState` | A 或 Mock 数据源 | 当前相机真实状态 |
| `capabilities` | A 或 Mock 数据源 | 当前相机合法能力 |
| `nowEpochMs` | 应用协调层 | 本次计算时间 |
| `userLocked` | B/应用状态 | 用户是否锁定参数 |
| `inputSource` | 应用协调层 | `REAL` 或 `MOCK` |
| `executionMode` | 应用协调层 | `REAL` 或 `MOCK` |

一次 `PolicyInput` 中的帧、语义、相机状态和能力应尽量对应同一时刻。

---

## 4. 用户意图：`UserIntent`

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
    val sourceText: String? = null,
    val createdAtEpochMs: Long = 0L
)
```

所有权重必须在：

```text
0.0..1.0
```

字段含义：

| 字段 | 含义 |
|---|---|
| `revision` | 用户意图版本，每次用户修改意图都递增 |
| `subjectDetail` | 优先看清人物、宠物、商品等主体 |
| `highlightDetail` | 优先保护天空、窗户、屏幕、灯牌等亮部 |
| `motionClarity` | 优先减少运动拖影 |
| `lowNoise` | 优先降低噪点 |
| `colorNeutrality` | 优先还原自然、中性的颜色 |
| `atmospherePreservation` | 优先保留现场暖色、冷色和彩色灯光氛围 |
| `exposureStability` | 不希望曝光频繁变化 |
| `sourceText` | 用户原始表达，仅用于展示和追溯 |
| `createdAtEpochMs` | 意图创建时间 |

自然语言不应直接传给 `PolicyEngine`。

正式流程：

```text
B 收集自然语言
→ D 后端或 Mock 解析器生成 UserIntent
→ B/协调层把 UserIntent 传给 C
```

示例：

```kotlin
val intent = UserIntent(
    revision = 3L,
    subjectDetail = 0.85f,
    highlightDetail = 0.85f,
    motionClarity = 0.0f,
    lowNoise = 1.0f,
    colorNeutrality = 0.0f,
    atmospherePreservation = 0.0f,
    exposureStability = 0.7f,
    sourceText = "人脸要清楚，天空不要过曝，夜景尽量少噪点",
    createdAtEpochMs = nowEpochMs
)
```

B 不应自行猜测或修改这些权重。

---

## 5. 画面指标：`VisionMetrics`

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

数值范围：

```text
subjectBrightness     0.0..1.0 或 null
backgroundBrightness  0.0..1.0 或 null
highlightRatio        0.0..1.0 或 null
darkRatio             0.0..1.0 或 null
motionScore           0.0..1.0 或 null
```

字段约束：

- 无法计算时使用 `null`，不能用 `0` 冒充有效值；
- `frameId` 必须和 `SceneSemantic.sourceFrameId` 对得上；
- 过期帧不能继续生成有效策略；
- `expiresAtEpochMs` 是该指标允许被使用的截止时间。

`VisionMetrics` 通常由 C 内部的 `FrameAnalyzer` 生成，B 不需要自己
计算亮度。

---

## 6. 场景语义：`SceneSemantic`

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

字段说明：

| 字段 | 含义 |
|---|---|
| `available` | 本次场景语义是否可用 |
| `scene` | 场景类型，例如 `indoor_backlight` |
| `subjectType` | 主体类型，例如 `person` |
| `brightRegionType` | 亮部区域类型，例如 `window` |
| `coloredLight` | 是否存在彩色光源 |
| `uncertainty` | 模型不确定度，范围 `0.0..1.0` |
| `reason` | 成功或失败原因 |
| `sourceFrameId` | 该语义对应的帧 ID |
| `receivedAtEpochMs` | 语义接收时间 |
| `expiresAtEpochMs` | 语义有效截止时间 |
| `intentRevision` | D 响应绑定的用户意图版本；不一致时 C 必须 HOLD |
| `uncertaintyNotes` | D v1 的 `uncertainty[]` 原文，仅用于保留说明和展示 |
| `analysisStatus` | D v1 的 `ok`、`mock` 或 `unavailable`，仅用于展示和诊断 |

模型失败时建议：

```kotlin
SceneSemantic(
    available = false,
    scene = null,
    subjectType = null,
    brightRegionType = null,
    coloredLight = null,
    uncertainty = 1.0f,
    reason = "timeout",
    sourceFrameId = frameId,
    receivedAtEpochMs = nowEpochMs,
    expiresAtEpochMs = null
)
```

C 不接触阿里云 API Key。API Key 只存在 D 的后端。

### 6.1 与 D `API_CONTRACT_V1_CANDIDATE.md` 的对应关系

D 的 HTTP 接口是：

```text
POST /api/v1/analyze-scene
```

C 侧对应的 Kotlin DTO 位于：

```text
core/src/main/kotlin/com/lightpilot/core/contract/v1/SceneAnalysisContract.kt
```

请求字段对应关系：

| D 的 JSON 字段 | C v1 DTO 字段 | 说明 |
|---|---|---|
| `frame_id` | `AnalyzeSceneRequest.frameId: Long` | D v1 要求 Int64；不能使用任意字符串 |
| `intent_revision` | `intentRevision: Long` | B 每次改变有效意图时递增 |
| `intent` | `intent: String` | 用户原话，供 D 场景分析使用 |
| `image_base64` | `imageBase64: String` | Android 负责传入代表帧图片 |
| `metrics.subject_brightness` | `metrics.subjectBrightness` | C 计算的归一化指标 |
| `metrics.highlight_ratio` | `metrics.highlightRatio` | C 计算的归一化指标 |
| `metrics.dark_ratio` | `metrics.darkRatio` | C 计算的归一化指标 |

响应字段对应关系：

| D 的 JSON 字段 | C 内部 `SceneSemantic` 字段 |
|---|---|
| `frame_id` | `sourceFrameId`，同时参与帧绑定校验 |
| `intent_revision` | `intentRevision`，同时参与意图版本校验 |
| `status=ok` | `available=true` |
| `status=mock/unavailable` | `available=false`，策略必须 HOLD |
| `scene` | `scene` |
| `subject_type` | `subjectType` |
| `bright_region_type` | `brightRegionType` |
| `colored_light` | `coloredLight` |
| `uncertainty[]` | `uncertaintyNotes` |
| `reason` | `reason`，仅展示和诊断，不参与策略匹配 |

`status` 同时保留到 `SceneSemantic.analysisStatus`，因此 B 可以区分
“Mock 联调结果”和“模型不可用”；该字段不会改变策略选择。

D 的 `uncertainty[]` 是说明文本，不是数值置信度。适配器采用保守规则：
空数组映射为 `uncertainty=0.0`，非空数组映射为 `uncertainty=1.0`，
并完整保留到 `uncertaintyNotes`。C 不解析 `reason` 或不确定性文本来生成动作。

Android/D 适配器使用：

```kotlin
val request = AnalyzeSceneRequest.fromMetrics(
    metrics = metrics,
    intentRevision = currentIntentRevision,
    intentText = rawUserIntent,
    imageBase64 = imageBase64
)

val semantic = V1SceneSemanticDataSource(client).readSemantic(
    request = request,
    nowEpochMs = nowEpochMs
)
```

适配器必须在语义进入 `PolicyEngine` 前校验：

```text
status == ok
response.frame_id == request.frame_id
response.intent_revision == request.intent_revision
```

任一条件不满足都转换为 `available=false`，由 C 保持安全状态。

---

## 7. 相机状态：`CameraState`

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
```

状态枚举：

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

约束：

- A 无法确认的参数使用 `null`；
- A 无法确认的状态使用 `UNKNOWN` 或 `null`；
- `connectionEpoch` 变化后，旧提案全部失效；
- `capabilityRevision` 变化后，旧提案全部失效；
- `currentEv` 不能用 `0.0` 代表未知。

---

## 8. 相机能力：`CameraCapabilities`

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

快门和参数类型：

```kotlin
data class ShutterSpeed(
    val numerator: Double,
    val denominator: Double
)

sealed interface ParameterTarget {
    data class Ev(val value: Double) : ParameterTarget
    data class Shutter(val value: ShutterSpeed) : ParameterTarget
    data class Iso(val value: Int) : ParameterTarget
    data class WhiteBalance(val value: Int) : ParameterTarget
}
```

`PolicyEngine` 不硬编码真实相机的 EV、ISO、快门和白平衡范围。
目标值必须来自 A 提供的能力列表。

当前已确认的参数名约定：

```text
exposureBias
exposureProgram
exposureShutterSpeed
ISO
whiteBalance
```

这些参数是否能在 GO Ultra 当前模式中读写，仍以 A 的真实 SDK 验证为准。

---

## 9. 策略输出：`PolicyProposal`

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
    val diagnostics: PolicyDiagnostics? = null,
    val executionMode: ExecutionMode = ExecutionMode.REAL
)
```

动作：

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

动作含义：

| 动作 | 含义 |
|---|---|
| `HOLD` | 当前不建议修改相机参数 |
| `EV_ONE_STEP_UP` | 提高到 `supportedEv` 中相邻的下一个合法值 |
| `EV_ONE_STEP_DOWN` | 降低到 `supportedEv` 中相邻的下一个合法值 |
| `SET_SHUTTER` | 设置快门；当前仅允许 Mock 展示 |
| `SET_ISO` | 设置 ISO；当前仅允许 Mock 展示 |
| `SET_WHITE_BALANCE` | 设置白平衡；当前仅允许 Mock 展示 |

提案的重要版本字段：

```text
proposalId
intentRevision
frameId
validUntilEpochMs
connectionEpoch
capabilityRevision
inputSource
executionMode
```

B 可以展示这些字段，但不能修改。

### P0 真实执行动作

当前 P0 只允许：

```text
HOLD
EV_ONE_STEP_UP
EV_ONE_STEP_DOWN
```

### 阶段二、三 Mock 动作

如果调用时：

```kotlin
executionMode = ExecutionMode.MOCK
```

C 可以从 Mock 能力列表中生成：

```text
SET_SHUTTER
SET_ISO
SET_WHITE_BALANCE
```

这些动作必须显示为“模拟建议”，不能当作真实执行成功，也不能提交
给 A。真实能力尚未确认时，`SafetyGuard` 会返回：

```text
allowed = false
reason = UNSUPPORTED_PARAMETER
```

---

## 10. 策略诊断：`PolicyDiagnostics`

```kotlin
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

该对象用于 UI 展示“为什么得到这个建议”，不是 UI 自己重新计算策略。

建议展示：

```text
主体风险
亮部风险
提亮分数
压暗分数
分数差
语义是否参与
```

---

## 11. 安全输出：`SafetyDecision`

```kotlin
data class SafetyDecision(
    val allowed: Boolean,
    val reason: SafetyReason,
    val message: String,
    val checkedProposalId: String?,
    val checkedAtEpochMs: Long
)
```

安全原因：

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

UI 处理规则：

| 条件 | UI 行为 |
|---|---|
| `allowed=true` | 可以进入等待用户确认状态 |
| `NO_ACTION` | 显示当前不需要调整 |
| `STALE_FRAME` | 等待新帧，不展示旧建议 |
| `STALE_INTENT` | 使用新意图重新计算 |
| `STALE_CAPABILITY` | 重新读取 A 的能力 |
| `CAMERA_BUSY` | 暂停执行 |
| `UNSUPPORTED_PARAMETER` | 显示当前参数尚未支持 |
| `EXPIRED_PROPOSAL` | 丢弃旧建议 |
| `DUPLICATE_COMMAND` | 禁止重复执行 |

---

## 12. 时序依赖：`TemporalDecision`

```kotlin
data class TemporalDecision(
    val metrics: VisionMetrics,
    val action: PolicyAction,
    val ready: Boolean,
    val reason: String
)
```

默认规则：

```text
连续 3 帧确认同一个动作
动作冷却时间 3 秒
候选动作变化时重新计数
HOLD 会清空待确认动作
```

常见 `reason`：

```text
awaiting_confirmation_1_of_3
awaiting_confirmation_2_of_3
confirmed
cooldown
candidate_hold
```

UI 不需要自己实现计数，只展示 `TemporalDecision.reason` 即可。

---

## 13. Mock 与真实模式

### Mock 模式

```kotlin
inputSource = InputSource.MOCK
executionMode = ExecutionMode.MOCK
```

规则：

- 可以展示 EV、快门、ISO、白平衡模拟建议；
- 不能调用真实相机；
- 不能显示“相机已调整成功”；
- 阶段二、三模拟建议的 `SafetyDecision.allowed` 必须为 `false`。

### Real 模式

```kotlin
inputSource = InputSource.REAL
executionMode = ExecutionMode.REAL
```

只有 A 提供真实状态、真实能力和合法目标值后，才允许进入真实联调。

---

## 14. B 的最小接入示例

```kotlin
val policyInput = PolicyInput(
    intent = userIntent,
    metrics = visionMetrics,
    semantic = sceneSemantic,
    cameraState = cameraState,
    capabilities = cameraCapabilities,
    nowEpochMs = System.currentTimeMillis(),
    userLocked = userLocked,
    inputSource = InputSource.REAL,
    executionMode = ExecutionMode.REAL
)

val cycleResult = policyCoordinator.evaluate(
    input = policyInput,
    commandId = commandId
)

val proposal = cycleResult.candidateProposal
val temporal = cycleResult.temporalDecision
val safety = cycleResult.safetyDecision

when {
    proposal.action == PolicyAction.HOLD ->
        showHold(proposal.reason)

    proposal.executionMode == ExecutionMode.MOCK ->
        showMockSuggestion(proposal)

    !safety.allowed ->
        showSafetyMessage(safety.message)

    !cycleResult.canRequestConfirmation ->
        showWaitingForFrames(temporal.reason)

    else ->
        showWaitingForUserConfirmation(proposal)
}
```

B 不得：

```text
自己计算 EV 目标值
自己修改 PolicyProposal
绕过 SafetyDecision 调用 SDK
把 Mock 建议提交给 A
把 PolicyProposal 当成 ExecutionResult
```

---

## 15. 与 A、D 的数据边界

### A → C

```text
CameraState
CameraCapabilities
PreviewFrame → VisionMetrics
```

### D → C

```text
D v1 /api/v1/analyze-scene → AnalyzeSceneResponse
AnalyzeSceneResponse → SceneSemantic（V1 适配器）
```

`UserIntent` 仍由 B/D 的意图解析链路提供；D 的场景分析请求中的
`intent` 是用户原话，不能替代 C 使用的结构化 `UserIntent`。

### C → B/A

```text
PolicyProposal
SafetyDecision
```

### A → B/C

```text
ExecutionResult
```

C 不调用 Insta360 SDK，不调用 HTTP，不保存阿里云 API Key。

---

## 16. 版本和变更规则

以下字段属于跨模块契约，修改前必须由 B、C 以及受影响成员确认：

```text
UserIntent
VisionMetrics
SceneSemantic
CameraState
CameraCapabilities
PolicyInput
PolicyProposal
SafetyDecision
PolicyAction
SafetyReason
InputSource
ExecutionMode
```

新增字段优先使用可空字段或默认值，不直接重命名或删除已有字段。
