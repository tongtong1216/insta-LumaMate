# 成员 C：相机接口快速上手

这份文档说明 C 如何使用 A 已完成的相机适配层。你不需要、也不应直接调用 Insta360 SDK。

## 1. 你只依赖一个接口

```kotlin
import com.example.insta_auto_adjust.camera.contract.CameraAdapter

class PolicyCoordinator(
    private val camera: CameraAdapter,
)
```

`CameraAdapter` 只有两类能力：

```kotlin
interface CameraAdapter : CameraSnapshotReader, PolicyProposalExecutor
```

| 调用 | 用途 |
| --- | --- |
| `camera.readSnapshot()` | 读取本次真实相机状态和当前合法参数列表 |
| `camera.executeConfirmed(...)` | 执行已经过安全校验、且用户已确认的提案 |

B 在应用装配层创建连接控制器后，将它作为 `CameraAdapter` 注入给 C：

```kotlin
val controller = Insta360ConnectionController(context)
val cameraAdapter: CameraAdapter = controller
```

C 不负责扫描、连接、权限、断开或 UI；这些由 B 调用 `Insta360ConnectionController` 处理。

## 2. 生成提案前必须读取快照

```kotlin
val snapshot = camera.readSnapshot()
val state = snapshot.state
val capabilities = snapshot.capabilities
```

`readSnapshot()` 会先同步 SDK 参数，再读取真实值；不要缓存旧快照来产生新提案。

重点字段：

- `state.connectionEpoch`：连接或重连后会变化；必须原样写入提案。
- `state.capabilityRevision`：相机模式或合法参数列表变化后会变化；必须原样写入提案。
- `state.currentEv`、`currentIso`、`currentShutterSpeed`、`currentWhiteBalance`：当前真实值；可能为 `null`。
- `state.exposureProgram`：ISO 与快门写入仅在 `MANUAL` 时可行。
- `state.isBusy`、`isWorking`、`recordingState`：不安全或无法确认时，C 的 `SafetyGuard` 应拒绝执行。
- `capabilities.supportedEv`、`supportedIso`、`supportedShutterSpeed`、`supportedWhiteBalance`：本次可接受的唯一目标值来源。

`null`、`UNKNOWN`、空合法列表都不是可猜测或可补默认值的情况，应生成 `HOLD` 或拒绝执行。

## 3. 生成合法的 PolicyProposal

提案必须带上刚读取快照的版本信息：

```kotlin
val proposal = PolicyProposal(
    proposalId = newCommandId(),
    intentRevision = userIntent.revision,
    frameId = visionMetrics.frameId,
    action = PolicyAction.EV_ONE_STEP_UP,
    parameter = null,
    reason = "subject_is_underexposed",
    risk = RiskLevel.LOW,
    cost = 0f,
    validUntilEpochMs = System.currentTimeMillis() + 3_000L,
    connectionEpoch = snapshot.state.connectionEpoch,
    capabilityRevision = snapshot.state.capabilityRevision,
    inputSource = InputSource.REAL_CAMERA,
)
```

动作和参数的匹配如下：

| `PolicyAction` | `parameter` |
| --- | --- |
| `HOLD` | `null` |
| `EV_ONE_STEP_UP` / `EV_ONE_STEP_DOWN` | `null` 表示一档；`EvTarget(合法 EV 值)` 表示逐档调至该值 |
| `SET_ISO` | `IsoTarget(合法 ISO 值)` |
| `SET_SHUTTER` | `ShutterSpeedTarget(合法快门值)` |
| `SET_WHITE_BALANCE` | `WhiteBalanceTarget(合法白平衡值)` |

构造目标的示例：

```kotlin
val iso = 800
require(iso in snapshot.capabilities.supportedIso)

val isoProposal = proposal.copy(
    proposalId = newCommandId(),
    action = PolicyAction.SET_ISO,
    parameter = IsoTarget(iso),
)
```

不要向 A 发送原始 SDK 参数名、任意浮点数、或未在 `supported*` 列表内的目标。

## 4. 安全校验、用户确认后执行

C 输出 `SafetyDecision`；B 展示提案并收集用户确认；然后 B 或协调层调用：

```kotlin
val result = camera.executeConfirmed(
    proposal = proposal,
    safetyDecision = safetyDecision,
    userConfirmed = userConfirmed,
)
```

仅在用户实际确认时传入 `userConfirmed = true`。适配层会再次读取相机，并自行拒绝以下情况：

- `SafetyDecision.allowed == false`；
- `checkedProposalId` 与 `proposalId` 不一致；
- 提案过期；
- `connectionEpoch` 或 `capabilityRevision` 已变化；
- 目标不在当前合法列表、相机忙碌或正在录制；
- ISO/快门不处于手动曝光模式。

因此，安全检查后到实际调用前若发生重连、模式切换或参数变化，旧提案不会写入相机。

## 5. 正确处理 ExecutionResult

```kotlin
when (result.status) {
    ExecutionStatus.SUCCEEDED -> {
        // 以 result.readback 为最终事实，而不是以 proposal.parameter 为准
    }
    ExecutionStatus.REJECTED, ExecutionStatus.SKIPPED -> {
        // 展示/记录 result.reason；不要重试相同 commandId
    }
    ExecutionStatus.FAILED -> {
        // 写入或读回不匹配，使用 result.readback 判断当前相机实际状态
    }
    ExecutionStatus.UNKNOWN -> {
        // 超时或连接改变：相机可能已收到写入，必须重新 readSnapshot() 后再决策
    }
}
```

`ExecutionResult` 中：

- `before` 是执行前读取的真实快照；
- `target` 是请求的类型化目标；
- `steps` 记录逐档写入及每档读回；
- `readback` 是最后的真实相机读回，优先级最高；
- 相同 `proposalId`/命令 ID 不应重复执行。

## 6. 当前边界

- 已真机验证读取与 EV、ISO、快门、白平衡的合法目标逐档写入。
- Android 已增加无 UI 的 GO Ultra 实时预览帧适配器：SDK 预览流中的 H.264/H.265 帧会在后台解码为 JPEG，再交给 D v1 场景分析接口。
- 手机不显示预览不代表没有预览帧；C 只能根据 `VisionMetrics.source = SDK_DECODED` 判断是否收到可分析的真实帧。
- Android 真实 D 联调路径的单帧分析窗口为 `35_000 ms`，对应 D 协议允许的 30 秒模型超时和 Android 请求余量；核心模块默认的严格 1.5 秒测试规则不变。
- `recordingState == UNKNOWN`、`isWorking == null` 等不可确认状态应按不安全处理。
- 本接口不含测试 UI、固定预设值或 Mock 执行逻辑。

## 7. 无 UI 实时帧链路

当前 Android 主流程为：

```text
GO Ultra 连接成功
→ CameraStreamListener.onStreamDataNotify
→ MediaCodec 后台解码
→ JPEG + VisionMetrics
→ POST /api/v1/analyze-scene
→ SceneSemantic
→ PolicyEngine / SafetyGuard
→ 用户确认
→ CameraAdapter.executeConfirmed
```

实现类：

```text
app/.../camera/insta360/Insta360RealtimePreviewFrameSource.kt
app/.../network/RealFrameImageReader.kt
```

`Insta360RealtimePreviewFrameSource` 不创建 `InstaCapturePlayerView`，因此不会要求手机显示预览。它复用已经连接的 `CameraDevice`，不会新建第二条 BLE/Wi-Fi 连接。

联调时应看到：

```text
实时预览帧已接入：frame_id=<数字>
```

如果没有收到帧，Android 不会回退到相册图片或拍照下载，而是保持安全状态并提示实时预览流未就绪。
