# LightPilot P0 实现与接入说明

D 与 C 的具体接口、调用顺序和联调验收见
[D_C_INTEGRATION_GUIDE.md](D_C_INTEGRATION_GUIDE.md)。

## 当前已实现

Android P0 核心位于 `app/src/main/java/com/example/insta_auto_adjust/lightpilot/`：

- `Models.kt`：rc2 固定枚举、意图、指标、语义、相机状态、建议和安全结果。
- `FrameAnalyzer.kt`：最长边约 320 像素采样的 BT.709 亮度指标。
- `PolicyEngine.kt`：只生成 `HOLD`、`EV_ONE_STEP_UP`、`EV_ONE_STEP_DOWN`。
- `TemporalController.kt`：普通模式 3 帧/3 秒，高稳定模式 5 帧/5 秒。
- `SceneSemanticCache.kt`：单请求、编号校验、60 秒缓存和亮度变化失效。
- `CameraAdapter.kt`：真机抽象、SafetyGuard、幂等执行、写后回读和超时失效。
- `BackendClient.kt`：rc2 JSON 请求和严格枚举响应解析，不包含密钥。
- `LightPilotCoordinator.kt`：串联低频 D 请求、本地逐帧策略、确认执行和冷却。

App 首页可以确认结构化意图、调整测试指标、提交连续本地帧并观察建议。默认
`FakeCameraAdapter` 的 `executionMode=MOCK`，点击执行会得到 `MOCK_EXECUTION`，不会把
模拟能力伪装成真机写入。

## B/C 调用顺序

1. B 让用户确认曝光优先级、稳定性和原始文本，递增 `intent_revision`。
2. C 对代表帧执行 `FrameAnalyzer`，用 `SceneSemanticCache.beginRequest(frameId)` 保证最多
   一个 D 请求在途。
3. 在后台线程调用 `LightPilotBackendClient.analyze()`；网络错误调用
   `finishFailure()`，成功结果交给 `accept()` 校验编号和状态。
4. 每个本地分析帧从缓存取语义，调用 `PolicyEngine.propose()`，再调用
   `TemporalController.observe()`。
5. 只有 `stableProposal` 非空且动作不是 `HOLD` 时，B 才显示用户确认按钮。
6. 用户确认后生成唯一 `commandId`，通过 `CameraExecutor.execute()`；不得直接调用
   SDK setter。
7. 成功执行后调用 `TemporalController.onExecuted()` 开始冷却，并展示
   `beforeEv/targetEv/readbackEv`。

`LightPilotBackendClient.analyze()` 是同步阻塞方法，必须从协程 IO dispatcher、Executor
或其他后台线程调用，不能在 Compose 主线程调用。

## A 的真实 SDK 接入点

A 新建一个 `CameraAdapter` 实现，完成：

```text
readCapabilities()
readState()
setEv(targetEv, commandId)
readCurrentEv()
markStateUnknown()
resyncState()
```

真实实现必须遵守：

- `supportedEv` 来自当前相机、模式和固件，不能写死；
- 能力或连接状态重建后递增 `cameraStateRevision`；
- 只有经过验证的真机能力才返回 `executionMode=REAL`；
- `setEv` 超时返回 `SetEvResult.Timeout`，不猜测是否成功；
- 超时后保持状态未知，直到 `resyncState()` 重新读取成功；
- `canWriteEvWhileRecording` 必须来自 SDK 验证结果，不能按产品猜测。

接入真实 SDK 后，只替换组合根中的 `FakeCameraAdapter`；PolicyEngine、TemporalController
和 SafetyGuard 不应引用厂商 SDK 类型。

## 后端地址

- Android 模拟器访问电脑：`http://10.0.2.2:8000`
- USB 真机：先运行 `adb reverse tcp:8000 tcp:8000`，再使用
  `http://127.0.0.1:8000`

Debug Manifest 允许本地明文 HTTP，Release 构建没有开启明文流量。生产环境需要 HTTPS、
服务端鉴权和网关限流。百炼 Key 只保存在后端 `.env`，不能放入 APK。

## 验证命令

```sh
# Android 单元测试与 Debug APK
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain

# 后端 rc2 测试
cd lightpilot-backend
.venv/bin/python -m pytest

# 服务启动后测试真实图片
.venv/bin/python -m scripts.smoke --require-live --show-result --repeat 3 \
  --exposure-priority balanced --stability-preference normal \
  --image "/absolute/path/to/photo.jpg"
```

自动化测试覆盖策略差异、冲突 HOLD、合法相邻 EV、能力边界、语义失败、3/5 帧时序、
缓存编号、亮度变化失效、Mock 拦截、命令幂等、成功读回和超时后状态未知。

## 尚需真实环境完成

- A 提供经过相机型号、固件和拍摄模式验证的 Insta360 SDK 及 `CameraAdapter` 实现。
- 将真实预览 Bitmap/ROI 接入 `FrameAnalyzer`，并在后台线程调用 D。
- 在华为真机上验证连接、录制状态写入能力、EV 支持列表、写后读回和断线恢复。
- 三类自有图片各连续调用三次，并运行真实视频抽帧报告。
- B/C/A/D 联合签字后，把后端协议从 `1.0.0-rc2` 改为 `1.0.0`。

上述真实环境检查完成前，项目保持 rc2，不能宣称真机闭环或 API v1 已冻结。
