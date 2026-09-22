# LightPilot D ↔ C 对接文档

- 协议版本：`1.0.0-rc2`
- 文档日期：2026-09-23
- D：百炼场景语义服务
- C：本地指标、EV 策略、时序控制和 SafetyGuard
- 当前状态：可以开始联调，尚未冻结为正式 v1

## 1. D 当前验收结果

本地视频 `IMG_1665.MOV` 已完成 6 帧真实调用，报告位于：

```text
lightpilot-backend/test-results/my-video-report.json
```

报告与后端日志共同证明请求实际经过百炼模型：

| 检查项 | 结果 |
| --- | --- |
| 后端模式 | `bailian` |
| 模型 | `qwen3.8-flash` |
| 模型配置 | `true` |
| HTTP 请求 | 6 次 `POST /api/v1/analyze-scene`，全部 200 |
| 协议有效帧 | 6/6 |
| `status=ok` | 6/6 |
| 场景 | 6/6 `outdoor_daylight` |
| 主体 | 6/6 `object` |
| 亮区 | 6/6 `none` |
| 彩色光 | 6/6 `false` |
| 枚举切换 | 0 次 |
| 延迟 | 7.691–28.536 秒，平均 19.768 秒 |

第 6 帧虽然 HTTP 和协议成功，但包含两条 `uncertainty`。D 将它视为有效模型输出；
C 的 P0 安全规则应将该帧视为不可执行语义并返回 HOLD。因此：

- `all_frames_ok=true` 表示 HTTP、协议和模型调用成功；
- 不表示每一帧都允许控制相机；
- 是否允许生成 EV 建议由 C 根据语义完整性和 SafetyGuard 决定。

平均延迟约 19.8 秒，D 必须作为低频语义上下文缓存，不能参与 C 的逐帧三帧/五帧确认。

## 2. 网络地址

后端接口：

```text
GET  /health
POST /api/v1/analyze-scene
```

开发地址：

| 场景 | Base URL |
| --- | --- |
| Android 官方模拟器 | `http://10.0.2.2:8000` |
| USB Android 真机 | 先执行 `adb reverse tcp:8000 tcp:8000`，再使用 `http://127.0.0.1:8000` |
| 同一局域网真机 | `http://电脑局域网IP:8000`，后端需监听 `0.0.0.0` |

Debug 构建允许本地明文 HTTP。百炼 API Key 只存在 D 后端，C 不保存、传输或打包 Key。

## 3. C 发给 D

请求：`POST /api/v1/analyze-scene`，`Content-Type: application/json`。

```json
{
  "frame_id": 152,
  "intent_revision": 3,
  "intent": {
    "exposure_priority": "subject_detail",
    "stability_preference": "normal",
    "source_text": "优先拍清楚人物，同时尽量保留背景灯光"
  },
  "image_base64": "...",
  "metrics": {
    "subject_brightness": 0.31,
    "background_brightness": 0.68,
    "highlight_clipping_ratio": 0.08,
    "dark_ratio": 0.42
  }
}
```

### 意图枚举

```text
exposure_priority:
- subject_detail
- highlight_detail
- balanced

stability_preference:
- normal
- high
```

`source_text` 只供 D 理解拍摄目的以及 B 展示。C 的 PolicyEngine 只能使用固定枚举，
不得解析 `source_text` 生成控制动作。

### 指标定义

C 在最长边不超过 320 像素的 sRGB 帧上计算：

```text
L = (0.2126R + 0.7152G + 0.0722B) / 255
```

| 字段 | 定义 |
| --- | --- |
| `subject_brightness` | 可靠主体 ROI 内平均 L；没有可靠 ROI 时为 `null` |
| `background_brightness` | 主体 ROI 外平均 L；没有主体 ROI 时使用全画面平均值 |
| `highlight_clipping_ratio` | 全画面 `L >= 0.98` 的像素比例 |
| `dark_ratio` | 全画面 `L <= 0.12` 的像素比例 |

所有指标范围为 `[0,1]`。`metrics` 整体可以为 null，但 C 无法取得必要指标时应 HOLD，
不能仅凭 D 的自然语言解释执行曝光动作。

### 图片约束

- JPEG、PNG、WebP 的纯 Base64 或 data URL；
- Android 使用 `Base64.NO_WRAP`；
- 解码后最大 4 MiB、1600 万像素；
- JSON 请求体最大 6 MiB；
- 一次只发送一张代表帧，不发送整段视频。

## 4. D 返回给 C

```json
{
  "frame_id": 152,
  "intent_revision": 3,
  "status": "ok",
  "scene": "indoor_mixed_light",
  "subject_type": "person",
  "bright_region_type": "display",
  "colored_light": false,
  "uncertainty": [],
  "reason": "主体较暗，背景存在明显高亮显示区域"
}
```

固定枚举以 D 的 OpenAPI 快照为准：

```text
lightpilot-backend/docs/openapi-v1.0.0-rc2.json
```

`reason` 只能显示或记录，不能参与策略。D 不返回 EV、动作、SDK 方法、合法参数列表或
相机能力。

## 5. C 接受语义的条件

响应只有同时满足以下条件才进入语义缓存：

```text
HTTP == 200
status == ok
response.intent_revision == currentIntentRevision
response.frame_id == latestRequestedModelFrameId
scene != null
subject_type != null
bright_region_type != null
colored_light != null
uncertainty 为空
```

任一条件不满足时：

1. 清除旧语义缓存；
2. 当前策略返回 `HOLD/SEMANTIC_UNAVAILABLE`；
3. 不发送任何相机 setter；
4. B 可以展示错误码或 `reason`，但不得把失败当成正常语义。

特别注意：本次视频第 6 帧的 `uncertainty` 非空，因此按 P0 规则必须 HOLD。

## 6. 调用频率和缓存

- 同时最多一个 D 请求；
- 第一次没有有效语义时保持当前相机设置；
- 有效语义缓存最长 60 秒；
- 意图变化、相机状态版本变化、模式变化时立即失效；
- 任一关键亮度指标相对缓存基线变化达到 `0.20` 时立即失效；
- 缓存失效后触发一次新的 D 请求；
- D 请求超时、网络失败、`mock` 或 `unavailable` 时清除旧缓存并 HOLD。

C 的普通模式连续 3 帧、高稳定模式连续 5 帧确认，全部使用本地 `VisionMetrics` 和
缓存的 `SceneSemantic`。不能为了三帧确认连续调用三次 D。

## 7. Android 已提供的对接代码

代码目录：

```text
app/src/main/java/com/example/insta_auto_adjust/lightpilot/
```

| 类 | C 的使用方式 |
| --- | --- |
| `Models.kt` | rc2 枚举、DTO、相机状态和策略建议 |
| `FrameAnalyzer` | 从 Bitmap 和主体 ROI 生成 `VisionMetrics` |
| `LightPilotBackendClient` | 将代表帧、意图和指标发送给 D |
| `SceneSemanticCache` | 单请求、编号校验、60 秒缓存和失效 |
| `PolicyEngine` | 生成 HOLD 或相邻一档 EV 建议 |
| `TemporalController` | 3/5 帧确认和 3/5 秒冷却 |
| `SafetyGuard` | 执行前校验过期、忙碌、能力、幂等和 Mock |
| `CameraExecutor` | 写入 EV 并读取实际值 |
| `LightPilotCoordinator` | 串联上述 P0 流程 |

`LightPilotBackendClient.analyze()` 和 `LightPilotCoordinator.refreshSemantic()` 是阻塞网络
调用，必须放在 IO dispatcher、Executor 或其他后台线程，不能在 Compose 主线程执行。

### 推荐接法

```kotlin
val metrics = FrameAnalyzer.analyze(
    bitmap = previewBitmap,
    frameId = frameId,
    subjectRoi = trackedSubjectRoi,
)

// 后台线程低频调用；返回 false 时保持 HOLD。
coordinator.refreshSemantic(previewBitmap, confirmedIntent, metrics)

// 每个本地分析帧调用，不会请求模型。
val temporal = coordinator.evaluateLocalFrame(confirmedIntent, metrics)
val proposal = temporal.stableProposal

// B 展示 proposal，用户明确确认后才执行。
if (proposal != null && proposal.action != ExposureAction.HOLD) {
    val record = coordinator.executeConfirmed(proposal, confirmedIntent)
    // 展示 record.beforeEv / targetEv / readbackEv / message
}
```

真实项目中 `refreshSemantic()` 由低频调度器调用；上面代码只表达数据关系，不能放在同一
主线程逐帧执行。

## 8. C 的 P0 HOLD 规则

C 必须在以下情况返回 HOLD：

- 没有有效缓存语义；
- D 返回 `mock`、`unavailable`、字段 null 或 `uncertainty` 非空；
- 主体优先但没有可靠主体 ROI；
- 主体偏暗且高光已经明显溢出；
- 当前 EV 不在最新 `supportedEv`；
- 已经到达 EV 上下限；
- 连续本地帧数量不足；
- 当前处于冷却期；
- 用户未确认建议；
- `intent_revision` 或 `camera_state_revision` 已变化；
- 建议生成超过 5 秒；
- 相机状态未知、忙碌或当前录制状态不允许写 EV；
- 目标不在最新能力列表；
- `commandId` 已使用；
- 相机或建议处于 Mock 模式。

## 9. 联调步骤

### D 启动后端

```bash
cd /Users/kugua/insta360-autu_adjust/lightpilot-backend
.venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

### C 检查后端

```bash
curl http://127.0.0.1:8000/health
```

真实模型联调应返回：

```json
{
  "status": "ok",
  "mode": "bailian",
  "model_configured": true,
  "model": "qwen3.8-flash"
}
```

### 联调验收顺序

1. C 使用 FakeCameraAdapter 接收固定 SceneSemantic，验证策略和三帧确认；
2. C 连接 D Mock，确认 `status=mock` 时始终 HOLD；
3. C 连接 D Bailian，发送单张真实代表帧；
4. 修改意图并让旧响应晚到，确认旧 `intent_revision` 被丢弃；
5. 制造网络超时，确认旧缓存被清除且没有相机命令；
6. 使用 A 提供的真实能力列表，确认只生成相邻一档 EV；
7. 用户确认后执行，记录 `beforeEv/targetEv/readbackEv`；
8. 相机写入超时时，将状态设为未知，直到重新同步成功。

## 10. C 对接完成标准

- C 使用 rc2 固定枚举，不解析 `reason` 或 `source_text` 控制相机；
- D 请求在后台运行且同时最多一个；
- 本地三帧/五帧确认没有重复请求 D；
- Mock、不可用、不确定、过期和编号不匹配均 HOLD；
- 所有 EV 目标来自 A 最新 `supportedEv` 的相邻值；
- 所有真实写入均经过用户确认和 SafetyGuard；
- UI 分别展示执行前值、目标值和读回值；
- 自动化测试、Android 到 D 联调和真机读回全部通过。

完成以上项目后，B/C/A/D 共同确认 rc2，再把协议版本改为正式 `1.0.0`。在此之前可以
开发和联调，但不能宣称 API 已冻结或真机闭环已经完成。
