# LightPilot 后端

为 Android 提供多阶段意图解析和场景语义分析。支持无需密钥的 Mock 联调，以及百炼
OpenAI-compatible 文本/图片调用。相机策略与 SafetyGuard 由 Android/C 负责，SDK 执行由 A 负责。

## 本机启动

要求 Python 3.11 或更新版本。本次使用 Python 3.11 验证，依赖安装到独立虚拟环境。

```sh
cd lightpilot-backend
python3.11 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements-dev.txt
# 首次配置时复制，不要覆盖已经填好的 .env
cp .env.example .env
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload
```

Windows 使用 `py -3.11 -m venv .venv` 和 `.venv\Scripts\Activate.ps1`。仅运行服务时安装 `requirements.txt` 即可。运行时依赖与测试依赖分别锁定；`.in` 文件描述直接依赖范围，`.txt` 锁定本次验证的完整依赖树。

- 健康检查：<http://127.0.0.1:8000/health>
- 交互接口文档：<http://127.0.0.1:8000/docs>
- OpenAPI 协议：<http://127.0.0.1:8000/openapi.json>
- rc3 OpenAPI 快照：[docs/openapi-v1.0.0-rc3.json](docs/openapi-v1.0.0-rc3.json)

`.env` 总是从此后端目录读取；同名进程环境变量优先。默认 `LIGHTPILOT_MODE=mock`，不会发起模型请求。修改 `.env` 后重启服务；不要依赖代码热重载检测 `.env`。

## 切换真实百炼模型

在本机 `.env` 中填写：

```dotenv
LIGHTPILOT_MODE=bailian
BAILIAN_API_KEY=填入自己的密钥
BAILIAN_BASE_URL=填入自己工作空间的HTTPS兼容接口地址
BAILIAN_MODEL_ID=qwen3.8-flash
BAILIAN_TIMEOUT_SECONDS=30
LIGHTPILOT_MAX_CONCURRENT=4
```

Base URL 必须是百炼控制台提供的 OpenAI-compatible 根地址，通常以 `/compatible-mode/v1` 结尾，不要填完整 `/chat/completions` 路径。密钥、地域与工作空间必须对应。模型名沿用指南的 `qwen3.8-flash`；账号可用性需要真实调用确认。

适配器使用 JSON 输出、关闭思考、最多 700 个输出 token；只有完整且通过严格协议校验的响应才返回 `ok`。模型返回动作字段、冒充请求编号、额外字段、截断结果、Markdown 或错误类型均返回 `unavailable`。不自动切换其他模型，不重试请求。每次调用有总超时，每个服务进程最多 4 个并发模型请求，容量不足快速返回 `backend_busy`。

未配置密钥或地址时，服务仍能启动，但真实模式分析返回 `unavailable/model_not_configured`。`/health` 只证明进程存活，`model_configured` 只表示配置存在，都不证明云端账号可用。

参考：[百炼模型能力](https://help.aliyun.com/zh/model-studio/qwen3-8-flash)、[结构化输出](https://www.alibabacloud.com/help/en/model-studio/qwen-structured-output)。最终用户只操作 Android，Swagger 页面仅用于开发。

## Android 接口协议 v1

`POST /api/v1/parse-intent` 把用户原文解析为三个独立权重和固定枚举：

```json
{
  "request_id": "24d45a9a-2f84-4c80-93c3-62e3c097ea5b",
  "source_text": "夜间跑步时拍清楚人物，同时保留霓虹灯颜色"
}
```

三个权重互相独立且必须位于 `[0,1]`；`>=0.50` 激活对应阶段。Android 严格校验模型
JSON，让用户补全激活但 priority 为 null 的阶段，只有点击确认后才递增
`intent_revision`。解析失败时进入手动选择，不生成相机动作。

`POST /api/v1/analyze-scene`，`Content-Type: application/json`。

```json
{
  "frame_id": 42,
  "intent_revision": 7,
  "intent": {
    "exposure_priority": "highlight_detail",
    "stability_preference": "normal",
    "source_text": "保留夜景氛围，同时让人物可辨认"
  },
  "image_base64": "这里替换为真实图片的Base64",
  "metrics": {
    "subject_brightness": 0.25,
    "background_brightness": 0.5,
    "highlight_clipping_ratio": 0.05,
    "dark_ratio": 0.4
  }
}
```

编号必须为非负整数，支持 Android `Long` 正值范围。场景请求中的 `intent` 保留 rc2 固定枚举；
`source_text` 可省略或为 null，存在时为 1–1000 个字符。`metrics` 可省略或为 null，
每个指标也可省略或为 null，数值统一为 0–1。rc1 的自由文本 `intent` 和
`highlight_ratio` 不再兼容，会返回 422。指标的 BT.709 定义和完整字段约束见候选协议。

图片支持纯 Base64，或 `data:image/jpeg;base64,...`、PNG/WebP 同类 data URL。Android 编码请使用 `Base64.NO_WRAP`。不支持远程图片 URL、动画或多帧图片。解码图片最多 4 MiB、1600 万像素，JSON 请求体最多 6 MiB；真实传入图片会在调用前校验格式、像素与可解码性。长边超过 1280 像素或带 ICC 色彩配置的图片，会在内存中转换为最长边 1280 像素的 sRGB JPEG 后再发送给模型；不修改或保存用户原图。

成功响应示例（仅作字段说明，不代表真实模型已验收）：

```json
{
  "frame_id": 42,
  "intent_revision": 7,
  "status": "ok",
  "scene": "night_low_light",
  "subject_type": "person",
  "bright_region_type": "lamp",
  "colored_light": true,
  "uncertainty": ["主体部分遮挡"],
  "uncertainty_details": [{
    "code": "subject_occluded",
    "severity": "warning",
    "affects": ["subject_type", "subject_roi"],
    "message": "主体部分遮挡"
  }],
  "reason": "霓虹灯形成彩色照明"
}
```

`scene`、`subject_type`、`bright_region_type` 使用 API v1 固定枚举；
`reason` 仅供展示、调试和人工验收，C 不得解析文本决定控制动作。Android 先检查
`status == "ok"`，再核对 `frame_id` 和当前 `intent_revision`，将结果作为最长 60 秒的
低频语义缓存；本地三帧/五帧确认不能连续请求模型。服务端无会话状态，不会替客户端
判断哪一帧已过期。完整约定见 `docs/API_CONTRACT_V1_CANDIDATE.md`。

| HTTP | 含义 | Android 处理 |
| --- | --- | --- |
| 200 / `ok` | 完整模型响应通过校验，语义仍可能不确定 | 核对编号，再交本地安全策略 |
| 200 / `mock` | 联调用模拟响应，语义字段为 null | 展示 Mock 标识并 HOLD |
| 200 / `unavailable` | 无配置、超时、模型错误或后端繁忙 | HOLD，不把 null 当成正常判断 |
| 422 | JSON、字段、指标或图片非法 | 修正请求，HOLD |
| 413 | 请求体过大 | 缩小代表帧，HOLD |
| 415 | 非 JSON 请求 | 使用正确 Content-Type |

rc2 客户端继续在 `uncertainty` 非空时整帧 HOLD。rc3 客户端读取
`uncertainty_details`：`blocking` 或 `affects=["all"]` 全部 HOLD；warning 只阻断依赖字段的
阶段。主体类别不确定不会阻断无关的阶段二运动策略，`colored_light` 不确定只阻断依赖
彩色光的阶段三策略。

## 手机与模拟器联调

- USB 真机：执行 `adb reverse tcp:8000 tcp:8000`，App 使用 `http://127.0.0.1:8000`。
- Android 官方模拟器：App 使用 `http://10.0.2.2:8000`。
- 同一局域网真机：以 `--host 0.0.0.0` 启动，App 使用电脑局域网 IP，并放行本机端口。

Android 已包含 `INTERNET` 权限、debug 明文网络配置和 rc3 客户端。后端默认仅监听本机且
未包含用户鉴权，不应直接发布到公网；公网部署前增加访问认证、HTTPS 和网关限流。

## 验证

```sh
python -m pytest
python -m pip check
# 服务已启动时执行，无需提供图片即可检查 Mock 协议，连续三次
python -m scripts.smoke
# 以下两项会实际调用百炼并产生用量，需要自己的密钥和图片
python -m scripts.smoke --kind text
# 经过 rc3 后端验证三阶段意图解析（三次真实百炼调用）
python -m scripts.smoke --kind intent --expect-all-stages --show-result --repeat 3 \
  --intent "夜间跑步时拍清楚人物，同时保留霓虹灯颜色"
python -m scripts.smoke --kind vision --image /path/to/your/photo.jpg
# 经过 HTTP 后端验证真实图片（先将服务切到 bailian 模式并重启）
python -m scripts.smoke --require-live --image /path/to/your/photo.jpg
# 显示每次经过校验的完整场景语义，并汇总字段稳定性与延迟
python -m scripts.smoke --require-live --show-result --repeat 3 --image /path/to/your/photo.jpg
# 从本地视频均匀抽取 6 帧，逐帧调用真实后端并生成 JSON 验收报告
python -m scripts.video_probe --video /path/to/video.mp4 --samples 6 \
  --exposure-priority balanced --stability-preference normal \
  --output test-results/video-probe-report.json
# 使用三份真实代表图片完成 D 的三阶段统一验收，每份连续调用三次
python -m scripts.three_stage_probe \
  --stage1-image /path/to/backlit.jpg \
  --stage2-image /path/to/motion.jpg \
  --stage3-image /path/to/colored-light.jpg \
  --repeat 3 --expect-stage3-colored-light \
  --output test-results/d-three-stage-report.json
```

测试通过本地 HTTP 替身覆盖真实 SDK 适配路径，不调用云端。冒烟脚本默认只输出每次状态和耗时，失败退出码为 1。显式添加 `--show-result` 后会额外显示通过 Pydantic 校验的 `SceneSemantic`，以及 `scene`、`subject_type`、`bright_region_type`、`colored_light` 的多次稳定性汇总；只有全部请求成功且字段非空并一致时，`integration_passed` 才为 `true`。脚本仍不会打印密钥、图片、未经校验的模型原文或上游异常正文。自动化测试结果不能替代三次真实图片稳定性检查，详见 `docs/ACCEPTANCE.md`。

`scripts.video_probe` 不把整段视频发送给后端。它使用本机 FFmpeg 抽取临时 JPEG，顺序
调用单帧接口，结束时删除临时帧，只保存指定的 JSON 报告。报告分别输出
`contract_passed`、各阶段 `stage_usable` 和 `field_stability`；阶段二不会因无关的主体类别
或彩色光波动直接失败。

D 的完整三阶段素材选择、报告字段、通过标准和与 C 的职责边界见
[D_THREE_STAGE_TEST_GUIDE.md](docs/D_THREE_STAGE_TEST_GUIDE.md)。

### 怎样确认 qwen3.8-flash 已接入

1. 在后端 `.env` 填写自己的 `BAILIAN_API_KEY` 和 `BAILIAN_BASE_URL`，设置 `BAILIAN_MODEL_ID=qwen3.8-flash`、`LIGHTPILOT_MODE=bailian`，随后重启后端。
2. 执行 `python -m scripts.smoke --kind text`。连续三次 `status=ok`、`passed=true`，说明密钥、地址和模型的文本调用成功。
3. 使用自有 JPEG/PNG/WebP 图片执行上面的 `--require-live --image` 命令。开头应显示 `mode=bailian`、`model=qwen3.8-flash`、`model_configured=true`，随后三次均为 `status=ok`、`passed=true`，才表示图片经过 HTTP 后端和真实模型并通过语义协议及编号校验。

单独看到 `/health` 的 `status=ok`、Swagger 能打开或 `model_configured=true`，均不等于真实模型调用成功。`--require-live` 会拒绝 Mock 服务，避免误判。

| 测试错误码 | 检查项 |
| --- | --- |
| `backend_is_mock` | 模式是否改为 bailian，服务是否已重启 |
| `model_not_configured` | 运行服务读取的环境变量或 `.env` 是否填写完整 |
| `authentication_failed` | 密钥是否有效，是否匹配工作空间与地域 |
| `model_or_endpoint_not_found` / `model_unavailable` | Base URL、模型名称和账号模型权限 |
| `rate_limited` | 账号额度与限流，稍后再试 |
| `timeout` / `connection_failed` | 网络、代理、接口地址或请求超时设置 |
| `invalid_model_response` | 模型是否支持图片和结构化输出，返回是否完整 |

## 数据与密钥

### 独立排查鉴权问题

可绕过 FastAPI 和 OpenAI SDK，用系统 curl 比较模型列表、兼容接口 HTTP/1.1、HTTP/2 和同工作空间的 DashScope 原生接口：

```sh
.venv/bin/python -m scripts.diagnose_connection --direct
```

`--direct` 让 curl 忽略环境变量中的代理，不会修改系统网络设置。若网络必须使用代理，可去掉该参数。测试只输出状态、错误码及耗时，不打印密钥与完整响应；三个推理检查在鉴权成功后会产生少量模型用量。请求不自动跟随重定向。

若怀疑 `.env` 中的截图识别结果与控制台原始密钥不一致，用下列命令直接粘贴原始 Key。输入不会显示，也不会写入 `.env` 或 shell 历史：

```sh
.venv/bin/python -m scripts.diagnose_connection --direct --prompt-key
```

`key_matches_project_config` 显示粘贴的 Key 是否与项目实际配置一致。如果为 false 且推理测试通过，说明需修正项目里保存的 Key。若原始 Key 在各接口均返回 401，则需进一步核对 Key 与工作空间/地域对应关系及控制台状态；仅凭 401 无法证明原始 Key 已失效。模型列表成功也不代表所选模型推理成功。

也可以使用控制台同一模型的在线体验检查账号与模型是否可用，但在线体验通常使用控制台登录态，不能代替对这枚 API Key 的验证。建议从同一工作空间的“API 调用示例”复制官方请求做对照。

参考：[百炼鉴权错误码](https://help.aliyun.com/zh/model-studio/error-code)、[原生调用示例](https://www.alibabacloud.com/help/fr/model-studio/text-generation)。

### 配置文件与数据处理

`.env`、虚拟环境、日志和测试缓存均被 Git 忽略；真实密钥只放服务器环境变量或本机 `.env`，不要放入 APK。Mock 不上传图片；bailian 模式会把当前图片、意图和指标发送至配置的百炼服务。后端不保存这些数据，不记录请求体、模型原文和原始异常。请求校验错误也不会回显图片。不要在联调时开启 SDK 的敏感 HTTP 调试日志。

本项目不需要数据库、Redis、CUDA 或本地模型。第三方组件说明见 `THIRD_PARTY_NOTICES.md`。
