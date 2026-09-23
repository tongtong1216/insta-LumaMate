# A/B 实时预览接口约定（草案）

> 状态：待 A、B 确认。依据 `origin/feature/android-ui`（2026-09-23 获取）和已验证的 Insta360 Android SDK 2.1.5 Demo 编写。
>
> 范围仅为 UI 实时预览；它不提供 C 可分析的解码帧，C 不依赖本文接口。

## 1. 整合前提

`feature/android-ui` 与 A 的 `dev_A_0` 是尚未合并的独立分支。它已提供 Compose 页面、`MainActivity` 和 `ShootingScreen`，但仍使用 `MOCK FRAME`、Mock 连接和 Mock 执行。合并时需人工处理两边共同修改的 Gradle 与 Manifest 配置；其中 UI 分支的 `minSdk = 24` 必须以 A 已验证的 `minSdk = 29` 为准。

B 应以 `dev_A_0` 为基线 rebase 或 cherry-pick UI 提交，并保留：

- `minSdk = 29`；
- A 的 `sdk-camera:2.1.5`、相机网络/蓝牙权限；
- B 的 Launcher Activity、Compose 依赖与 A 的相机配置在同一 Manifest / Gradle 文件中共存。

A 为预览增加同版本 `sdk-media:2.1.5`。合并时应保留双方所需配置，而不是以任一分支的 Gradle 或 Manifest 整体覆盖另一方。

## 2. 责任边界

| 责任 | A | B | C |
| --- | --- | --- | --- |
| SDK 预览流、player、pipeline | 实现和释放 | 不直接调用 | 不接触 |
| 相机连接、断连、epoch | 已实现并继续管理 | 触发连接、展示状态 | 不接触 SDK |
| Compose 容器和生命周期 | 提供接口 | 实现 | 不接触 |
| `frameSource` | 真实首帧后更新 | 只展示，不修改 | 不将 UI 预览视为分析帧 |
| 参数读写回归 | 确保不被预览破坏 | 触发验收 | 继续通过 `CameraAdapter` |

## 3. A 提供的预览接口

接口放入独立 `camera/preview/` 包，不加入纯 Kotlin 的 `camera/contract/CameraContracts.kt`，也不扩展 C 使用的 `CameraAdapter`。

```kotlin
enum class PreviewPhase {
    IDLE, STARTING, RENDERING, STOPPING, FAILED, DISCONNECTED,
}

data class PreviewUiState(
    val phase: PreviewPhase = PreviewPhase.IDLE,
    val message: String? = null,
    val renderedAtEpochMs: Long? = null,
)

interface CameraPreviewController {
    val previewState: StateFlow<PreviewUiState>

    /** 主线程调用；同一时刻只能绑定一个容器。 */
    fun attach(container: ViewGroup)
    /** 已连接且 attach 后启动；重复调用必须幂等。 */
    fun start()
    /** 停流并解绑 pipeline；重复调用必须幂等。 */
    fun stop()
    /** 先 stop，再销毁 SDK player 并从容器移除。 */
    fun detach()
}
```

`ViewGroup` 是 Android 平台类型，不是 SDK 类型。A 在容器内创建和销毁 `InstaCapturePlayerView`，B 不导入或持有 SDK player。接口暂不接收通用 `Surface`：已验证的 Demo 路径为 player + pipeline，未经真机验证前不承诺任意 `Surface` 直出。

状态语义：

- `STARTING` 不表示已有画面。
- 只有 pipeline 已绑定且 player 已回调真实首帧，才能进入 `RENDERING`；此时 A 才将同一会话 `frameSource` 更新为 `SDK_RENDERED_PREVIEW`。
- 失败、停止、解绑或断连后不得保留该帧来源；断连进入 `DISCONNECTED` 并将其恢复为 `UNKNOWN`。

## 4. B 必须调整的部分

### 4.1 页面装配（重点）

`MainActivity.handleConnectionClick()` 当前通过连续点击模拟连接，`handleMockAnalysis()` 生成 Mock 帧与建议。B 必须：

1. 在 Activity 或 ViewModel 创建一个长期存活的 `Insta360ConnectionController`，不可在 Compose 重组中创建。
2. 以 `CameraAdapter` 注入 C；以 `CameraPreviewController` 提供拍摄页。
3. 用运行时权限、`initializeAndScan()`、设备选择、`connectViaBluetoothWifi()`、`requestBleAuthorization()` 替换 Mock 连接。
4. 将 A 的连接状态和真实 `CameraSnapshot` 映射为 `CameraUiState`；删除第二次点击即成功和 `DataSource.MOCK`。
5. 未真实连接时，禁用进入拍摄页、启动预览和“分析当前画面”。

### 4.2 `ShootingScreen` 预览区（重点）

当前私有 `PreviewPanel()` 固定显示 `MOCK FRAME`。B 必须：

- 用 `AndroidView` 创建 `FrameLayout`，调用 `CameraPreviewController.attach()`；移除 Mock 文案。
- 收集 `previewState`，显示“启动中”“预览中”“失败/断连”及重试入口；`RENDERING` 前不得显示成功。
- `AndroidView` 容器销毁时调用 `detach()`，页面离开、后台、断连时调用 `stop()`。
- 容器可见且已连接时调用 `start()`；同一容器的重组/重复 `attach()` 必须幂等，确认弹窗等 Overlay 不能重复创建 player。

### 4.3 安全边界

- 用户接受建议后仍必须调用 `CameraAdapter.executeConfirmed(...)`，不得以预览状态或 UI 缓存代替真机回读。
- C 不得取得 `ViewGroup`、SDK player、pipeline 或预览流；UI 预览不是 `SDK_DECODED`，不能直接产出 `VisionMetrics`。

## 5. A 的实现要求

1. 复用当前连接的同一 `CameraDevice`，不创建第二条 BLE/Wi-Fi 会话。
2. 按 Demo 路径：初始化 preview、注册 listener、启动 stream、prepare/play player、绑定 pipeline；真实首帧后才进入 `RENDERING`。
3. 固定释放顺序：注销 listener → `setPipeline(null)` → `stopStream()` → 销毁 player。
4. 在 `disconnect()`、SDK 断连回调、`close()` 中无条件停止和解绑预览。
5. 用连接 epoch 和预览启动 nonce 丢弃旧会话、旧 View 的异步回调。
6. 协调预览启停与 `syncAllParams()`、已确认写入，避免破坏性并发。

## 6. 联调验收

1. 真实连接后进入拍摄页，持续显示 GO Ultra 画面。
2. 首帧前不是“预览中”；首帧后 `frameSource == SDK_RENDERED_PREVIEW`。
3. 旋转、后台/前台、离开并返回拍摄页不崩溃、不保留旧 player；画面可恢复。
4. 断连停止输出并展示断连；重连后可再次预览。
5. 预览运行时，参数轮询仍反映相机端更改；已确认写入及拒绝路径保持正确。
6. 不提交 SDK 二进制、仓库凭据、Wi-Fi 密码、APK 或敏感日志。

## 7. 确认项

- [ ] A 确认上述接口可由 Demo 已验证路径实现。
- [ ] B 确认 `ViewGroup + AndroidView` 承载预览，不要求未经验证的通用 `Surface`。
- [ ] B 确认页面离开/后台时停止预览的产品语义。
- [ ] A、B 确认以 `dev_A_0` 为基线整合 UI，不删除现有相机层。
