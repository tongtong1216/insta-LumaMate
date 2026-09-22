# LightPilot Android ↔ D 后端 API v1 候选协议

- 协议版本：`1.0.0-rc2`
- 状态：候选，等待 B/C 联调和真实图片验收
- 负责方：D（后端与模型接入）
- 最后更新：2026-09-23

rc2 是冻结前的破坏性升级：不兼容 rc1 的自由文本 `intent` 和
`metrics.highlight_ratio`。联调与验收通过后标记为 `1.0.0`；之后的破坏性修改进入
`/api/v2`。

## 1. 接口

- `GET /health`：只证明服务和配置状态可读取，不证明云端模型调用成功。
- `POST /api/v1/analyze-scene`：分析一张代表帧，`Content-Type: application/json`。

## 2. Android 请求

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

| 字段 | 类型 | 必填 | 约束与含义 |
| --- | --- | --- | --- |
| `frame_id` | Int64 | 是 | 非负；每个提交给 D 的代表帧单调递增 |
| `intent_revision` | Int64 | 是 | 非负；用户确认的有效意图变化时递增 |
| `intent.exposure_priority` | Enum | 是 | `subject_detail`、`highlight_detail`、`balanced` |
| `intent.stability_preference` | Enum | 是 | `normal`、`high` |
| `intent.source_text` | String/null | 否 | 1–1000 字；只供模型理解和 B 展示，C 不解析文本制定策略 |
| `image_base64` | String | 是 | JPEG、PNG 或 WebP 的纯 Base64 或 data URL |
| `metrics` | Object/null | 否 | C 计算的归一化指标；缺失时 D 仍可分析图片 |
| `metrics.subject_brightness` | Float/null | 否 | `[0,1]`；可靠主体 ROI 的平均亮度 |
| `metrics.background_brightness` | Float/null | 否 | `[0,1]`；主体外区域或全画面的平均亮度 |
| `metrics.highlight_clipping_ratio` | Float/null | 否 | `[0,1]`；亮度 `L >= 0.98` 的像素比例 |
| `metrics.dark_ratio` | Float/null | 否 | `[0,1]`；亮度 `L <= 0.12` 的像素比例 |

亮度定义为缩小后的 sRGB 帧上的 BT.709 归一化亮度：

```text
L = (0.2126R + 0.7152G + 0.0722B) / 255
```

建议最长边先缩小到 320 像素。没有可靠主体 ROI 时
`subject_brightness=null`，`background_brightness` 使用全画面。所有额外字段均拒绝。

图片解码后最大 4 MiB、1600 万像素，请求体最大 6 MiB。Android 使用
`Base64.NO_WRAP`。后端会在内存中把长边超过 1280 像素或带 ICC 配置的图片转换为
最长边 1280 像素的 sRGB JPEG，不保存或修改原图。

## 3. D 返回

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

| 字段 | 类型 | 含义 |
| --- | --- | --- |
| `frame_id` | Int64 | D 从原请求绑定并原样返回，模型不能提供或修改 |
| `intent_revision` | Int64 | D 从原请求绑定并原样返回，模型不能提供或修改 |
| `status` | Enum | `ok`、`mock`、`unavailable` |
| `scene` | Enum/null | 固定光照场景枚举 |
| `subject_type` | Enum/null | 固定主体类别枚举 |
| `bright_region_type` | Enum/null | 固定主要亮区来源枚举 |
| `colored_light` | Boolean/null | 是否存在明显彩色光 |
| `uncertainty` | String[] | 不确定性；失败时首项为稳定错误码 |
| `reason` | String/null | 只供展示、日志诊断和人工验收 |

`reason` 是非策略字段。C 不得解析或匹配它来决定 EV 或相机动作。D 不能返回
EV、曝光参数、动作、SDK 方法、策略原因码或相机能力。

### 固定枚举

`scene`：

```text
indoor_even_light, indoor_mixed_light, indoor_low_light, indoor_backlit,
outdoor_daylight, outdoor_backlit, night_low_light, stage_colored_light,
high_contrast_other, other
```

`subject_type`：

```text
person, group, display, document, object, landscape, none, other
```

`bright_region_type`：

```text
none, sky, window, display, lamp, specular_reflection, mixed, other
```

## 4. 状态和失败处理

| HTTP/状态 | 客户端处理 |
| --- | --- |
| `200/ok` | 核对编号后更新低频语义缓存，再交给 C |
| `200/mock` | 显示 Mock，必须 HOLD |
| `200/unavailable` | 显示失败原因，必须 HOLD |
| `422` | 请求不符合 rc2，修正后重试，必须 HOLD |
| `413` | 缩小代表帧，必须 HOLD |
| `415` | 改用 JSON，必须 HOLD |

稳定失败码包括 `model_not_configured`、`timeout`、`rate_limited`、
`authentication_failed`、`connection_failed`、`invalid_model_response`、
`model_unavailable`、`backend_busy`、`internal_error`。

客户端同时最多一个模型请求。响应只有满足以下条件才进入缓存：

```text
status == ok
response.intent_revision == currentIntentRevision
response.frame_id == latestRequestedModelFrameId
```

模型语义是低频上下文，不参与本地三帧计数。建议缓存 60 秒；意图、相机状态版本、
模式变化或关键亮度指标变化达到 0.20 时失效。C 的每帧指标、时序稳定和 SafetyGuard
在 Android 本地运行。

## 5. 视频与验收

`scripts.video_probe` 只在 D 的电脑上对本地视频均匀抽取代表帧，顺序调用单帧接口，
记录语义、原因、延迟和枚举变化。它不上传整段视频，也不替代 C 的实时本地时序控制。

```sh
.venv/bin/python -m scripts.video_probe \
  --video "/absolute/path/to/video.mp4" \
  --samples 6 \
  --exposure-priority balanced \
  --stability-preference normal \
  --output "test-results/video-probe-report.json"
```

冻结前需完成：三类真实图片各连续三次、真实视频抽帧、B/C rc2 对接、Mock/失败 HOLD、
编号过期丢弃、固定枚举验证。验收事实写入 `docs/ACCEPTANCE.md`；未执行的真机测试不得
标记为通过。

## 6. 责任边界

- B：确认结构化意图、维护 `intent_revision`、展示状态和解释。
- C：计算指标、缓存语义、生成单步 EV 建议、时序控制和 SafetyGuard。
- A：提供真实能力列表、写入相机并读取实际值。
- D：图片语义、协议校验、模型调用和失败归一化。

四方验收完成前保持 `1.0.0-rc2`，不能提前宣称 v1 已冻结。
