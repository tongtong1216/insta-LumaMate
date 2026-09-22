# LightPilot Android ↔ D 后端 API v1 候选协议

- 协议版本：`1.0.0-rc1`
- 状态：候选，等待 B/C 确认与三类真实图片验收
- 负责方：D（后端与模型接入）
- 最后更新：2026-09-22

本文件是 B、C、D 的唯一候选接口约定。正式冻结后，v1 不再增加、删除或改名字段，也不再改变枚举值和字段语义；破坏性修改进入 `/api/v2`。

## 1. 接口

### 健康检查

`GET /health`

该接口只证明后端进程和配置状态可读取，不证明云端模型调用成功。

### 场景分析

`POST /api/v1/analyze-scene`

请求头：`Content-Type: application/json`

## 2. Android 请求

```json
{
  "frame_id": 152,
  "intent_revision": 3,
  "intent": "优先拍清楚主体，同时尽量保留背景灯光",
  "image_base64": "...",
  "metrics": {
    "subject_brightness": 0.31,
    "highlight_ratio": 0.08,
    "dark_ratio": 0.42
  }
}
```

| 字段 | 类型 | 必填 | 约束与含义 |
| --- | --- | --- | --- |
| `frame_id` | Int64 | 是 | 非负；A/C 为每个提交帧生成单调递增编号 |
| `intent_revision` | Int64 | 是 | 非负；B 每次改变有效拍摄意图时递增 |
| `intent` | String | 是 | 去除首尾空白后 1–1000 字符 |
| `image_base64` | String | 是 | JPEG、PNG 或 WebP 的纯 Base64 或对应 data URL |
| `metrics` | Object/null | 否 | C 计算的归一化指标；缺失时 D 仍可分析图片 |
| `metrics.subject_brightness` | Float/null | 否 | `[0,1]`，主体亮度 |
| `metrics.highlight_ratio` | Float/null | 否 | `[0,1]`，高亮区域占比 |
| `metrics.dark_ratio` | Float/null | 否 | `[0,1]`，暗部区域占比 |

额外字段一律拒绝。图片解码后最大 4 MiB、1600 万像素；请求体最大 6 MiB。Android 使用 `Base64.NO_WRAP`。后端会在内存中把长边超过 1280 像素或带 ICC 色彩配置的图片规范化为最长边 1280 像素的 sRGB JPEG，不修改或保存原图。

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
| `frame_id` | Int64 | D 从原始请求绑定并原样返回，模型不能提供或修改 |
| `intent_revision` | Int64 | D 从原始请求绑定并原样返回，模型不能提供或修改 |
| `status` | Enum | `ok`、`mock` 或 `unavailable` |
| `scene` | Enum/null | 固定光照场景枚举 |
| `subject_type` | Enum/null | 固定主体类别枚举 |
| `bright_region_type` | Enum/null | 固定主要亮区来源枚举 |
| `colored_light` | Boolean/null | 是否存在明显影响主体或场景的彩色光 |
| `uncertainty` | String[] | `ok` 时为不确定性说明；失败时首项为稳定错误码 |
| `reason` | String/null | 中文人工解释，仅供 B 展示、日志诊断和人工验收 |

`reason` 是非策略字段。C 不得搜索、匹配或解析 `reason` 文本来决定曝光、EV 或相机动作。

## 4. 固定枚举

### `scene`

| 值 | 含义 |
| --- | --- |
| `indoor_even_light` | 室内整体光照较均匀 |
| `indoor_mixed_light` | 室内存在多种光源、色温或明显亮暗区域 |
| `indoor_low_light` | 室内整体低照度 |
| `indoor_backlit` | 室内主体前景较暗、背景明显更亮 |
| `outdoor_daylight` | 室外日光，未形成显著逆光问题 |
| `outdoor_backlit` | 室外主体相对背景明显逆光 |
| `night_low_light` | 夜间或近似夜间的低照度环境 |
| `stage_colored_light` | 舞台、演出或明显彩色灯光环境 |
| `high_contrast_other` | 不属于上述类别的高反差环境 |
| `other` | 图像可分析，但不属于上述场景 |

### `subject_type`

| 值 | 含义 |
| --- | --- |
| `person` | 单人是主要主体 |
| `group` | 多人群体是主要主体 |
| `display` | 屏幕、显示器或投影内容是主要主体 |
| `document` | 纸张、书页或文字材料是主要主体 |
| `object` | 普通物体或商品是主要主体 |
| `landscape` | 风景或整体环境是主要主体 |
| `none` | 没有可识别的主要主体 |
| `other` | 有主体，但不属于上述类别 |

### `bright_region_type`

| 值 | 含义 |
| --- | --- |
| `none` | 没有明显集中亮区 |
| `sky` | 天空是主要亮区 |
| `window` | 窗口或门外区域是主要亮区 |
| `display` | 显示屏或投影是主要亮区 |
| `lamp` | 灯具、灯牌或主动发光装置是主要亮区 |
| `specular_reflection` | 镜面、高光或反射是主要亮区 |
| `mixed` | 同时存在多个不同来源的主要亮区 |
| `other` | 有明显亮区，但不属于上述类别 |

模型无法可靠判断时返回 `null`，并在 `uncertainty` 中解释；不得创造新枚举或使用中文同义词替代枚举。

## 5. 状态与 HTTP 行为

| HTTP/状态 | 含义 | Android 行为 |
| --- | --- | --- |
| `200 / ok` | 模型结果完整并通过 Pydantic 校验 | 再检查两个编号，交给 C |
| `200 / mock` | 仅为联调模拟结果，语义字段为 null | 显示 Mock，HOLD |
| `200 / unavailable` | 云端超时、限流、鉴权、连接、格式或容量异常 | HOLD，可按错误码提示或退避 |
| `413` | 请求体超过 6 MiB | 缩小代表帧，HOLD |
| `415` | Content-Type 不是 JSON | 修正客户端请求 |
| `422` | JSON、字段、指标或图片非法 | 修正客户端请求，HOLD |

`unavailable` 时所有语义字段为 null，`uncertainty[0]` 是以下稳定错误码之一：

`model_not_configured`、`timeout`、`rate_limited`、`authentication_failed`、`connection_failed`、`invalid_model_response`、`model_unavailable`、`backend_busy`、`internal_error`。

后端模型总超时为 30 秒。Android 请求超时建议至少 35 秒，并限制为最多一个在途分析请求，避免旧帧堆积。

## 6. 过期结果处理

Android 维护：

- `currentIntentRevision`：B 当前意图版本；
- `latestRequestedFrameId`：当前意图下最后提交给 D 的帧；
- `lastAppliedFrameId`：C 已应用的最后一帧。

响应满足以下全部条件才能进入 C：

```text
status == "ok"
response.intent_revision == currentIntentRevision
response.frame_id == latestRequestedFrameId
response.frame_id > lastAppliedFrameId
```

任一条件不满足即丢弃并保持当前安全状态。D 是无会话服务，只负责回传原始编号；过期判断由 Android 完成。

## 7. B、C、D 职责边界

- B：维护用户意图与 `intent_revision`，展示状态、枚举和 `reason`。
- C：生成指标，核对编号，只使用固定枚举、布尔值、指标和 `uncertainty` 做策略输入；执行 Safety Guard。
- D：校验请求、调用模型、约束模型 JSON、绑定原始编号、返回稳定失败码。
- A：提供相机帧、能力与实际操作接口；D 不返回 EV、动作或 SDK 方法。

## 8. 候选协议转正式冻结条件

- B、C 确认字段可空性、枚举含义、HOLD 和过期丢弃规则；
- 普通室内、暗主体亮背景、强光或彩色光三类真实图片各连续调用 3 次；
- 三类测试均为 HTTP 200、`status=ok`、稳定 JSON，枚举合法，`reason` 基于画面；
- timeout、429、非法响应和编号过期路径均由测试覆盖；
- Android DTO 与本文件一致。

满足上述条件后，将状态改为 `Frozen`、版本改为 `1.0.0`。此后任何字段、可空性、枚举值或状态语义的破坏性修改都进入 `/api/v2`。
