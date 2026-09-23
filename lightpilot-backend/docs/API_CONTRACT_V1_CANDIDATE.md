# LightPilot Android ↔ D 后端 API v1 候选协议

- 协议版本：`1.0.0-rc3`
- 状态：候选，等待 B/C 联调、真实图片和视频验收
- 负责方：D（意图解析、场景语义和协议校验）
- 最后更新：2026-09-23

rc3 在 rc2 的单帧场景接口上增加多阶段意图解析和字段级不确定性。rc2 客户端仍可读取
原有字段并在 `uncertainty` 非空时整帧 `HOLD`；rc3 客户端只使用
`uncertainty_details.code/affects/severity` 制定字段级降级策略。四方验收通过后才标记为
`1.0.0`，之后的破坏性修改进入 `/api/v2`。

## 1. 接口

- `GET /health`：进程和配置状态，不证明云端调用成功。
- `POST /api/v1/parse-intent`：用百炼把用户文本解析成三个独立权重和固定枚举。
- `POST /api/v1/analyze-scene`：分析一张代表帧并返回场景语义。

所有 POST 请求使用 `Content-Type: application/json`，额外字段一律拒绝。

## 2. 多阶段意图解析

请求：

```json
{
  "request_id": "24d45a9a-2f84-4c80-93c3-62e3c097ea5b",
  "source_text": "夜间跑步时拍清楚人物，同时保留霓虹灯颜色"
}
```

`request_id` 由 Android 生成 UUID。`source_text` 为 1–1000 个非空字符。

成功响应：

```json
{
  "request_id": "24d45a9a-2f84-4c80-93c3-62e3c097ea5b",
  "status": "ok",
  "intent": {
    "weights": {
      "exposure": 0.8,
      "motion_noise": 0.95,
      "color_atmosphere": 0.85
    },
    "exposure_priority": "subject_detail",
    "motion_priority": "motion_clarity",
    "color_priority": "colored_light_preservation",
    "stability_preference": "high"
  },
  "ambiguities": [],
  "reason": "用户同时强调人物清晰、运动冻结和霓虹氛围"
}
```

三个权重分别位于 `[0,1]`，互相独立，不要求总和为 1。权重 `>=0.50` 的阶段激活。
未激活阶段的 priority 可以为 `null`；已激活阶段无法确定 priority 时必须为 `null`，
并在 `ambiguities` 中要求用户手动选择。只有用户确认所有激活阶段后，B 才递增
`intent_revision`。解析失败进入手动选择，不生成相机动作。

固定枚举：

```text
exposure_priority: subject_detail, highlight_detail, balanced
motion_priority: motion_clarity, low_noise, brightness_priority, motion_balanced
color_priority: color_accuracy, natural_skin, atmosphere_preservation,
                colored_light_preservation, color_stability
stability_preference: normal, high
```

Mapper 严格拒绝非数字、布尔权重、NaN、Infinity、越界值、缺失字段、额外字段和未知枚举，
不做字符串猜测或自动裁剪。模型不能提供或修改 `request_id`，也不能返回 EV、ISO、快门、
白平衡、相机动作或 SDK 方法。

## 3. 场景分析请求

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

| 字段 | 约束与含义 |
| --- | --- |
| `frame_id` | 非负 Int64；代表帧编号 |
| `intent_revision` | 非负 Int64；用户确认的意图版本 |
| `intent` | 保留 rc2 结构；供图片模型理解语境，不负责多阶段策略 |
| `image_base64` | JPEG、PNG、WebP 的 Base64 或 data URL |
| `metrics` | 可整体缺失；存在的数值必须位于 `[0,1]` |

指标亮度定义：

```text
L = (0.2126R + 0.7152G + 0.0722B) / 255
```

- `subject_brightness`：可靠主体 ROI 平均 L，无可靠 ROI 时为 `null`。
- `background_brightness`：主体外区域平均 L，无 ROI 时为全画面平均值。
- `highlight_clipping_ratio`：全画面 `L >= 0.98` 的像素比例。
- `dark_ratio`：全画面 `L <= 0.12` 的像素比例。

C 可将最长边缩小到 320 像素再计算。图片解码后最大 4 MiB、1600 万像素，请求体最大
6 MiB；后端只在内存中把长边超过 1280 或带 ICC 的图片转换为 sRGB JPEG。

## 4. 场景分析响应

```json
{
  "frame_id": 152,
  "intent_revision": 3,
  "status": "ok",
  "scene": "indoor_mixed_light",
  "subject_type": "person",
  "bright_region_type": "display",
  "colored_light": false,
  "uncertainty": ["主体被树叶部分遮挡"],
  "uncertainty_details": [
    {
      "code": "subject_occluded",
      "severity": "warning",
      "affects": ["subject_type", "subject_roi"],
      "message": "主体被树叶部分遮挡"
    }
  ],
  "reason": "主体较暗，背景存在明显高亮显示区域"
}
```

`frame_id` 和 `intent_revision` 由服务端从请求绑定，模型不能提供或修改。`reason` 和
`uncertainty_details[].message` 都是展示文本，C 不解析自然语言制定策略。

`affects` 固定为：

```text
scene, subject_type, subject_roi, bright_region_type, colored_light,
image_quality, all
```

`severity` 固定为 `warning` 或 `blocking`。服务失败使用 `blocking + affects=[all]`。
模型语义 code 固定为：

```text
scene_uncertain, subject_type_uncertain, subject_occluded,
bright_region_uncertain, colored_light_uncertain, image_blur,
image_too_dark, image_quality_uncertain, other
```

服务失败码包括：

```text
model_not_configured, model_not_connected, timeout, rate_limited,
authentication_failed, connection_failed, invalid_model_response,
model_unavailable, backend_busy, internal_error
```

`uncertainty` 保留为兼容字符串数组。rc2 客户端看到非空数组继续整帧 `HOLD`；rc3 客户端：

- `blocking`、`affects=all`、模型失败：全部阶段 `HOLD`；
- 主体类别或 ROI 不确定：只阻断依赖人物/ROI 的策略；
- `colored_light` 不确定：只阻断依赖彩色光的阶段三策略；
- 无关 warning 仍展示，但不阻断阶段二全局运动策略。

场景固定枚举仍与 rc2 相同：

```text
scene: indoor_even_light, indoor_mixed_light, indoor_low_light, indoor_backlit,
       outdoor_daylight, outdoor_backlit, night_low_light, stage_colored_light,
       high_contrast_other, other
subject_type: person, group, display, document, object, landscape, none, other
bright_region_type: none, sky, window, display, lamp, specular_reflection, mixed, other
```

## 5. C 的缓存和仲裁约定

响应进入缓存前必须满足：

```text
status == ok
response.intent_revision == currentIntentRevision
response.frame_id == latestRequestedModelFrameId
没有 blocking 或 affects=all
```

同时最多一个模型请求；语义缓存最长 60 秒。意图、相机重连、模式变化或关键亮度指标变化
达到 0.20 时立即失效。三帧/五帧确认只使用 C 的本地指标，不连续请求 D。

三个阶段独立生成候选，分数为 `stage_weight × urgency`。仲裁器每轮最多选择一个动作；
前两名差值 `<0.10` 时 `HOLD/MULTI_OBJECTIVE_CONFLICT`。最高权重阶段因能力、Mock、模式
或语义依赖被阻断时，不静默执行低权重动作。阶段二、三在真机执行器验收前始终为
`MOCK/planning_only`。

## 6. HTTP 和失败处理

| HTTP/状态 | 客户端处理 |
| --- | --- |
| `200/ok` | 严格解析并核对请求编号 |
| `200/mock` | 显示 Mock，禁止执行 |
| `200/unavailable` | 显示失败，意图改手选或场景整阶段 HOLD |
| `422` | 字段不符合 rc3，修正后重试 |
| `413` | 缩小代表帧 |
| `415` | 改用 JSON |

## 7. 视频和验收报告

`scripts.video_probe` 只抽取本地视频代表帧，不上传整段视频。`scripts.three_stage_probe`
的每阶段摘要分为：

```text
contract_passed  接口、模型状态和编号正确
stage_usable     该阶段依赖字段可用
field_stability  各语义字段的独立稳定性记录
```

阶段二不会因无关的 `subject_type` 或 `colored_light` 波动直接失败；字段波动仍记录，供使用
这些字段的阶段查看。验收命令见 `D_THREE_STAGE_TEST_GUIDE.md`。

## 8. 责任边界

- B：调用意图解析、让用户补选并确认、维护 `intent_revision`、展示警告。
- C：本地指标、字段依赖、候选评分、冲突仲裁、时序和 SafetyGuard。
- A：真实能力、模式切换、单参数写入和写后回读。
- D：百炼意图解析、图片语义、固定枚举、结构化不确定性和失败归一化。

四方验收完成前保持 `1.0.0-rc3`，不宣称 API v1 已冻结。
