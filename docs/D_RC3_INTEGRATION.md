# D ↔ C 1.0.0-rc3 联调说明

Android/C 已按 `doc/D_C_INTEGRATION_GUIDE(1).md` 的场景分析契约发送：

- `intent.exposure_priority`：`subject_detail`、`highlight_detail`、`balanced`；
- `intent.stability_preference`：`normal`、`high`；
- `intent.source_text`：仅传给 D 和用于 UI 展示，C 不解析该文本生成相机动作；
- `metrics.subject_brightness`、`background_brightness`、`highlight_clipping_ratio`、`dark_ratio`；
- 一张 JPEG 代表帧，Base64 使用 Android `NO_WRAP`。

## 地址配置

默认地址是 `http://127.0.0.1:8000`。USB 真机先执行：

```powershell
adb reverse tcp:8000 tcp:8000
```

模拟器或局域网真机在用户级 `gradle.properties` 配置，不要提交个人地址：

```properties
LIGHTPILOT_BACKEND_URL=http://10.0.2.2:8000
```

## C 侧安全行为

- D 网络调用在 IO dispatcher 执行，客户端同时最多允许一个请求；
- `mock`、`unavailable`、编号不匹配、blocking、`affects=all` 均不进入缓存；
- 语义缓存最长 60 秒，意图、相机状态、模式或关键亮度指标变化 `>= 0.20` 时失效；
- rc3 结构化 warning 只阻断依赖字段；没有 `uncertainty_details` 的旧响应仍按整帧 HOLD；
- 没有可靠主体 ROI 时，主体优先策略 HOLD；高光策略仍可使用全局指标；
- EV 目标只取最新能力列表中的相邻一档，真实写入仍需用户确认和 SafetyGuard。

## 尚需 D 补齐的契约文件

规范引用的 `lightpilot-backend/docs/openapi-v1.0.0-rc3.json` 当前不在仓库中，仓库内 D 后端代码仍是 rc1 请求结构。`/api/v1/parse-intent` 的完整 rc3 响应 JSON 也未写在集成文档中，因此 Android 暂未臆造该响应 DTO。联调前请 D 提供 OpenAPI 快照并更新后端实现，再据快照补接意图解析响应。
