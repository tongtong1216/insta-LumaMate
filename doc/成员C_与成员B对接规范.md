# LightPilot 成员 C 与成员 B 对接规范

## 1. 文档目的

本文档用于冻结成员 B 与成员 C 之间的数据、调用顺序和责任边界。

核心原则：

```text
B：收集用户意图、展示结果、等待用户确认
C：分析画面并生成受约束的策略建议和安全判定
A：调用 Go Ultra SDK 执行已确认的相机操作并回读结果
D：提供后端场景语义服务
```

C 不负责：

- 直接调用 Insta360 SDK；
- 直接调用阿里云百炼或保存 API Key；
- 直接修改相机参数；
- 把策略建议伪装成相机已经执行成功。

---

## 2. C 与 B 的完整数据流

```text
B/UI
  └─ 用户自然语言
       ↓
 B 的 UserIntentResolver
       ↓
   UserIntent
       ↓
A 提供 CameraState、CameraCapabilities 和预览帧
D 提供 SceneSemantic
       ↓
C：FrameAnalyzer + PolicyEngine
       ↓
C：PolicyCoordinator
       ↓
C：TemporalController + SafetyGuard
       ↓
PolicyProposal + SafetyDecision
       ↓
B 展示建议和理由
       ↓
B 等待用户确认
       ↓
A 执行相机 SDK 操作
       ↓
ExecutionResult
       ↓
B 展示执行状态，C/A 校验 readback
```

注意：

```text
PolicyProposal = 建议怎么调
SafetyDecision = 当前是否允许继续执行
ExecutionResult = 相机实际执行和回读的结果
```

三者不能混为一谈。

---

## 3. B 需要提供给 C 的输入

### 3.1 `UserIntent`

B 应该提供自然语言输入入口。B 不能把自然语言直接传给 `PolicyEngine`，
而应先通过 `UserIntentResolver` 或 D 的后端接口转换成结构化权重，再传给 C。

预设按钮可以保留，但只能作为快捷输入和调试入口。预设本质上也应生成
一段 `sourceText`，再经过同一个解析/映射流程，不能成为唯一的交互方式。

当前 Android Mock 阶段使用本地关键词解析器，仅用于验证链路：

```text
用户原话
  ↓
LocalKeywordIntentResolver（Mock）
  ↓
UserIntent
  ↓
PolicyEngine
```

接入 D 后，替换为：

```text
用户原话
  ↓
B 调用 D 提供的后端接口
  ↓
结构化 UserIntent
  ↓
PolicyEngine
```

D 的解析服务应返回结构化字段和解析状态；API Key 仍只保留在 D 的后端。

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

所有权重必须在 `0.0..1.0` 范围内。

字段含义：

| 字段 | 含义 |
|---|---|
| `subjectDetail` | 优先保留人物、宠物、商品等主体细节 |
| `highlightDetail` | 优先保留天空、窗户、屏幕、灯牌等亮部细节 |
| `motionClarity` | 优先冻结运动，减少拖影 |
| `lowNoise` | 优先降低噪点 |
| `colorNeutrality` | 优先还原中性、自然的颜色 |
| `atmospherePreservation` | 优先保留现场暖色、冷色和彩色灯光氛围 |
| `exposureStability` | 不希望画面因轻微变化频繁调整 |

推荐的 P0 预设：

```text
SUBJECT_FIRST
HIGHLIGHT_FIRST
BALANCED
STABLE_EXPOSURE
```

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
  "createdAtEpochMs": 1790035200000
}
```

### 3.2 用户锁定状态

B 需要把用户是否锁定相机参数传给 C：

```kotlin
userLocked: Boolean
```

当用户手动锁定参数时：

```text
userLocked = true
```

C 必须输出 `HOLD`，不能生成新的调参建议。

用户解除锁定后，B 应提高 `UserIntent.revision`，再触发新一轮策略计算。

### 3.3 策略计算请求

B 主要负责提供 `UserIntent` 和 `userLocked`。应用协调层再把 B、A、D
提供的数据组装成一次策略计算请求：

```text
UserIntent
VisionMetrics
SceneSemantic
CameraState
CameraCapabilities
userLocked
nowEpochMs
inputSource
executionMode
```

其中：

```text
B：UserIntent、userLocked
A：CameraState、CameraCapabilities、预览帧
D：SceneSemantic
C：VisionMetrics、PolicyProposal、SafetyDecision
```

这些数据必须属于同一帧或同一版本，不能把新帧和旧语义、旧能力混在一起。

---

## 4. B 从 C 接收什么

### 4.1 `VisionMetrics`

`VisionMetrics` 是 C 对预览帧进行本地分析后的结果。B 可以展示其中的摘要，但不能把它直接称为“画质评分”。

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
subjectBrightness       0.0..1.0
backgroundBrightness    0.0..1.0
highlightRatio          0.0..1.0
darkRatio               0.0..1.0
motionScore             0.0..1.0 或 null
```

不可计算时使用 `null`，不能用 `0` 假装有效。

### 4.2 `PolicyProposal`

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
```

P0 允许的动作只有：

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

但阶段一实际只允许：

```text
HOLD
EV_ONE_STEP_UP
EV_ONE_STEP_DOWN
```

`SET_SHUTTER`、`SET_ISO`、`SET_WHITE_BALANCE` 在 A 尚未完成真实 SDK 能力验证前，不得在 UI 中显示为可执行动作。

### 4.3 `SafetyDecision`

```kotlin
data class SafetyDecision(
    val allowed: Boolean,
    val reason: SafetyReason,
    val message: String,
    val checkedProposalId: String?,
    val checkedAtEpochMs: Long
)
```

常见状态：

| 状态 | B 的显示和行为 |
|---|---|
| `ALLOWED` | 可以显示“等待用户确认”，不能直接执行 |
| `NO_ACTION` | 显示“当前不需要调整” |
| `STALE_FRAME` | 等待新帧，不展示旧建议为当前建议 |
| `STALE_INTENT` | 重新生成意图和建议 |
| `STALE_CAPABILITY` | 重新读取相机能力 |
| `CAMERA_BUSY` | 显示相机忙，暂不执行 |
| `RECORDING` | 按项目规则暂停调参或等待录制状态变化 |
| `UNKNOWN_CAMERA_STATE` | 显示无法确认相机状态，禁止执行 |
| `ILLEGAL_TARGET` | 不得调用 SDK，记录错误 |
| `DUPLICATE_COMMAND` | 不得重复执行 |
| `USER_LOCKED` | 尊重用户锁定，不继续建议 |
| `UNSUPPORTED_PARAMETER` | 显示当前模式不支持该参数 |
| `EXPIRED_PROPOSAL` | 丢弃建议并重新分析 |
| `MISSING_COMMAND_ID` | 不允许进入真实执行流程 |

---

## 5. B 的页面状态规范

B 应将 C 的结果映射为清晰的页面状态：

```text
ANALYZING
SUGGESTION_READY
WAITING_USER_CONFIRMATION
HOLD
EXECUTING
EXECUTION_SUCCESS
EXECUTION_FAILED
STALE
CAMERA_BUSY
MODEL_UNAVAILABLE
```

推荐流程：

```text
ANALYZING
  ↓ C 输出 allowed=true 的 PolicyProposal
SUGGESTION_READY
  ↓ B 展示理由并等待点击确认
WAITING_USER_CONFIRMATION
  ↓ 用户确认
EXECUTING
  ↓ A 返回 ExecutionResult
EXECUTION_SUCCESS / EXECUTION_FAILED
```

以下情况不能进入 `EXECUTING`：

- `proposal.action == HOLD`；
- `SafetyDecision.allowed == false`；
- `proposal.validUntilEpochMs` 已过期；
- `inputSource == MOCK`；
- `executionMode == MOCK`；
- 用户确认的 `proposalId` 与当前建议不一致；
- 用户确认后相机连接版本或能力版本已经变化。

特别注意：

```text
Mock 结果可以展示为“模拟建议”
Mock 结果不能展示为“相机已执行成功”
```

---

## 6. B 如何处理用户确认

B 负责确认交互，但不应自己拼接相机参数。

正确流程：

```text
B 展示 PolicyProposal
  ↓
用户点击确认
  ↓
B 将 proposalId、commandId 和原始 PolicyProposal 交给 A
  ↓
A 再次检查状态后调用 SDK
```

真实执行请求至少需要：

```text
commandId
proposalId
action
parameter
intentRevision
frameId
connectionEpoch
capabilityRevision
```

`commandId` 必须唯一。B 不得使用固定字符串，也不得因为页面重组或重复点击而重复执行同一命令。

建议：

```text
commandId = app-session + proposalId + user-confirmation-sequence
```

B 不得只传：

```text
setEv(1.0)
```

因为这样会丢失帧版本、能力版本、用户意图版本和提案有效期信息。

---

## 7. B 如何展示具体 EV

当 C 输出：

```text
action = EV_ONE_STEP_UP
parameter = Ev(1.0)
```

B 可以展示：

```text
建议：提高曝光补偿
目标 EV：+1.0
原因：优先保留人物主体，当前主体偏暗
风险：MEDIUM
```

当 C 输出：

```text
action = EV_ONE_STEP_DOWN
parameter = Ev(-1.0)
```

B 可以展示：

```text
建议：降低曝光补偿
目标 EV：-1.0
原因：优先保留窗户/天空亮部细节
风险：MEDIUM
```

B 不得把 `EV_ONE_STEP_UP` 自己解释成固定加 `1`。具体目标值已经由 C 根据 A 提供的 `supportedEv` 合法列表计算完成。

例如：

```text
supportedEv = [-2.0, -1.0, -0.5, 0.0, 0.5, 1.0]
currentEv = -0.5
EV_ONE_STEP_UP = 0.0
```

因此 B 只展示 `parameter`，不要自行计算目标值。

---

## 8. B 可以展示但不能修改的字段

B 可以展示：

```text
reason
risk
cost
diagnostics
scene
subjectType
brightRegionType
semantic uncertainty
frameId
inputSource
executionMode
```

B 不得修改：

```text
proposalId
frameId
intentRevision
connectionEpoch
capabilityRevision
validUntilEpochMs
parameter
inputSource
executionMode
```

如果用户改变意图，B 应创建新的 `UserIntent.revision`，然后让 C 重新生成完整提案，而不是在旧提案上直接修改 `parameter`。

---

## 9. 与 A、D 的边界

### 9.1 B、C、A 的边界

```text
B：展示、确认、状态管理
C：计算建议、安全判定
A：SDK 执行、状态读取、写入后回读
```

C 输出给 B 和 A：

```text
PolicyProposal
SafetyDecision
```

A 执行后输出：

```text
ExecutionResult
```

至少应包含：

```text
commandId
proposalId
executionStatus
beforeValue
targetValue
readbackValue
readbackStatus
errorCode
connectionEpoch
capabilityRevision
inputSource
executionMode
```

B 负责展示 `ExecutionResult`，C 负责用它验证策略闭环，但 C 不调用 SDK。

### 9.2 B、C、D 的边界

```text
D：调用后端模型，返回结构化 SceneSemantic
C：使用 SceneSemantic 参与策略判断
B：展示必要的场景解释和策略理由
```

B 和 C 都不应持有阿里云 API Key。

模型失败时：

```text
available = false
reason = timeout / model_unavailable / rate_limited / ...
```

B 可以显示“场景识别暂不可用”，但不能把旧语义当作新画面结果继续执行。

### 9.3 D v1 场景分析接口接入方式

D 提供的候选接口见：

```text
doc/API_CONTRACT_V1_CANDIDATE.md
POST /api/v1/analyze-scene
```

Android 侧不能把 D 的 JSON 响应直接当作 `PolicyEngine` 输入。正确链路是：

```text
B 提供用户原话、intent_revision
      +
C 生成 VisionMetrics，并提供代表帧 Base64
      ↓
AnalyzeSceneRequest（D v1）
      ↓
D 返回 AnalyzeSceneResponse
      ↓
V1SceneSemanticDataSource
      ↓
SceneSemantic
      ↓
PolicyCoordinator / PolicyEngine
```

D v1 的关键绑定字段：

```text
frame_id          Int64，响应必须原样返回
intent_revision   Int64，响应必须原样返回
status            ok / mock / unavailable
uncertainty       String[]，不能当作数值权重
reason            只能展示和诊断，不能被 C 用关键词匹配
```

`status=mock` 或 `status=unavailable` 必须转换为
`SceneSemantic.available=false`，C 会输出 `HOLD`。响应帧号或意图版本不匹配
时同样不能进入有效策略。

B 需要配合提供：

1. 当前用户原话；
2. 当前 `intent_revision`；
3. 让 C 能获得原话对应的结构化 `UserIntent`；
4. 展示 `reason`、`uncertaintyNotes` 和模型不可用状态。

B 不需要实现 C 的 DTO 映射，也不需要持有 API Key。

---

## 10. Mock 阶段的强制规范

当前尚未接入 A 的真实 SDK 和 D 的真实后端时，B 页面必须明确显示：

```text
input_source=mock
execution_mode=mock
```

推荐显示：

```text
当前为模拟策略演示，不连接真实相机，也不会修改相机参数。
```

Mock 阶段允许：

- 切换“优先主体”“优先亮部”“整体平衡”；
- 展示“运动清晰”“低噪点”“自然色彩”“保留氛围”等阶段二/三模拟建议；
- 展示不同 `PolicyAction`；
- 展示评分、理由、风险和安全判定；
- 展示连续确认进度和“是否允许真实执行”；
- 验证按钮和页面状态流转。

Mock 阶段禁止：

- 显示“相机已调整成功”；
- 把 Mock `ALLOWED` 当成真实 SDK 已执行；
- 把 `SET_SHUTTER`、`SET_ISO`、`SET_WHITE_BALANCE` 模拟提案提交给 A；
- 使用真实 API Key；
- 使用真实相机成功文案；
- 伪造 `ExecutionResult`。

阶段二/三模拟提案的固定规则：

```text
proposal.executionMode = MOCK
SafetyDecision.allowed = false
```

目标值只能从 Mock `CameraCapabilities` 的合法列表中选择；列表为空时
必须返回 `HOLD` 和能力缺口原因，不能硬编码 GO Ultra 的真实范围。

P0 EV 建议仍然需要经过 `PolicyCoordinator` 的连续 3 帧确认和
`SafetyGuard` 预检。Mock EV 也不能直接显示为真实相机执行成功。

---

## 11. B 交给 C 的确认清单

B 与 C 对接前，需要确认：

```text
[ ] UserIntent 字段和权重范围已统一
[ ] revision 每次意图变化都会递增
[ ] sourceText 只作为原始用户表达，不直接进入策略计算
[ ] userLocked 能正确传递
[ ] B 不自行计算 EV 目标值
[ ] B 不修改 PolicyProposal
[ ] B 能区分 HOLD、建议可确认、执行中、执行成功、执行失败
[ ] B 能展示 input_source 和 execution_mode
[ ] Mock 结果不会显示成真实相机成功
[ ] 用户确认时会携带 proposalId 和唯一 commandId
[ ] 提案过期、帧变化、意图变化、能力变化后不会继续执行
[ ] B 不保存或提交 API Key
```

---

## 12. 给成员 B 的最小交接内容

可以直接将以下内容发给 B：

```text
成员 C 的模块接收结构化 UserIntent、VisionMetrics、SceneSemantic、
CameraState、CameraCapabilities 和 userLocked。

C 输出 PolicyProposal 和 SafetyDecision。

B 负责：
1. 收集或展示用户意图；
2. 接收用户自然语言，并通过约定的 `UserIntentResolver` 转成结构化 `UserIntent`；
3. 展示 C 的建议、理由、风险和安全状态；
4. 等待用户确认；
5. 将原始 proposalId、唯一 commandId 和提案交给 A；
6. 展示 A 返回的 ExecutionResult。

B 不负责：
1. 自己计算 EV；
2. 自己拼接或修改相机参数；
3. 绕过 SafetyDecision 直接调用 SDK；
4. 把 Mock 结果展示成真实执行成功；
5. 接触阿里云 API Key。
```

---

## 13. 版本和变更规则

以下字段属于跨模块契约，修改前必须由 C 和 B 共同确认：

```text
UserIntent
VisionMetrics
PolicyProposal
SafetyDecision
PolicyAction
SafetyReason
inputSource
executionMode
```

新增字段应遵守：

1. 优先新增可空字段或有默认值的字段；
2. 不直接重命名已有字段；
3. 不改变已有枚举含义；
4. 不删除 `proposalId`、`frameId`、`revision`、版本号和有效期字段；
5. 同时更新本文档和核心测试；
6. 由至少一名受影响成员确认后再合并。

本次对接新增的 C 侧 V1 类型位于：

```text
com.lightpilot.core.contract.v1.AnalyzeSceneRequest
com.lightpilot.core.contract.v1.AnalyzeSceneResponse
com.lightpilot.core.contract.v1.SceneAnalysisClient
com.lightpilot.core.contract.v1.V1SceneSemanticDataSource
```

它们只描述协议和适配，不包含 HTTP 客户端、阿里云 SDK 或 API Key。
