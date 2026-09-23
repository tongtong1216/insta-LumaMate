# 后端验收记录

验证日期：2026-09-23。环境：macOS Apple Silicon、Python 3.11.12，独立 `.venv`，依赖版本见 `requirements*.txt`。

## 已完成

### rc3 本地实现验证（2026-09-23）

- 后端 rc3 自动化：94 项测试通过，`pip check` 无破损依赖。
- Android：45 项单元测试通过，`:app:assembleDebug` 成功。
- OpenAPI 快照 `openapi-v1.0.0-rc3.json` 与运行时 schema 完全一致。
- 已覆盖 `/parse-intent` 三个独立权重、边界、非数字/布尔/缺失/额外字段、模型注入，
  以及已激活 priority 缺失时的手动补选。
- 已覆盖结构化不确定性、rc2 整帧 HOLD 兼容、阶段字段级降级、三/五帧确认、分数冲突、
  最高目标阻断、每轮单动作和阶段二/三 `MOCK/planning_only` 拦截。
- rc3 `/parse-intent` 已用真实百炼连续调用 3 次：全部 HTTP 200/`status=ok`，三个阶段均
  激活，固定 priority 三次一致；延迟约 5023 / 4810 / 3919 ms。权重存在小幅波动，需以
  用户最终确认值为准。
- 新版结构化图片提示已用 `IMG_1665.MOV` 中点代表帧真实调用 1 次：HTTP 200、
  `status=ok`、三个阶段 `stage_usable=true`、`uncertainty_details=[]`，约 24787 ms。报告为
  `test-results/rc3-one-frame-video-report.json`；仍需三类素材各连续三次完成正式稳定性验收。

| 检查 | 实际结果 |
| --- | --- |
| 自动化测试 `python -m pytest` | 66 passed |
| Android P0 单元测试与 Debug APK | 17 tests passed；`:app:assembleDebug` 通过 |
| Android 模拟器运行 | 安装成功，`MainActivity` 启动 `Status: ok`，P0 控件可见 |
| Android 三帧与过期保护 | 默认指标连续三帧得到 `EV_ONE_STEP_UP/target=1.0`；5 秒后执行被 `EXPIRED` 拦截 |
| 依赖检查 `python -m pip check` | No broken requirements found |
| Uvicorn 启动 | `127.0.0.1:8000`，startup complete |
| 实际 HTTP `/health` | `status=ok`、`mode=bailian`、`model=qwen3.8-flash`、`model_configured=true` |
| 实际 HTTP Mock 场景分析 | 连续 3 次成功，编号一致；约 11 / 2 / 2 ms，仅为本机 Mock 耗时 |
| 百炼真实文本调用 | 新凭证连续 3 次成功；约 2190 / 1614 / 826 ms |
| 百炼真实视觉调用 | 非个人模拟器截图直连成功，约 4431 ms |
| 后端到百炼完整 HTTP 调用 | `--require-live` 成功，`status=ok`，约 11756 ms |
| 真实图片完整 HTTP 调用 | 原图自动规范化后成功，`integration_passed=true`，约 20445 ms |
| API v1 候选枚举真实调用 | `outdoor_daylight / group / sky / false`，约 15071 ms |
| 视频抽帧端到端调用 | 2 秒合成视频中点帧成功；协议有效，约 5785 ms，报告已生成 |
| OpenAPI 与 Swagger | 测试通过，包含两个接口与响应状态枚举 |
| Git 忽略 | `.env` 与 `.venv` 被忽略 |

自动化测试包含真实 OpenAI SDK 通过本地 HTTP 替身的调用路径，覆盖成功语义、模型注入编号/动作、非法 JSON、错误字段类型、截断、429、401/403、400/404/500、连接失败、总超时、并发容量与恢复、非法输入、图片 MIME 不符、超大/分块请求，以及错误返回与日志不包含上游敏感正文。Mock 与真实模式缺少配置的响应明确区分。

追加验证：`--require-live` 拒绝 Mock 或配置不全的服务，HTTP 冒烟拒绝编号不匹配的成功响应，错误诊断不回显上游原文。`/health` 会显示真实模式使用的模型名称，仍不代表模型已成功调用。

旧凭证鉴权排查：使用系统 curl，绕过 FastAPI/OpenAI SDK 和环境代理，对旧工作空间请求模型列表、兼容模式 HTTP/1.1、兼容模式 HTTP/2、DashScope 原生多模态接口，四项均返回 HTTP 401（`invalid_api_key` / `InvalidApiKey`）。北京共享地址和 CC Switch 3.20.2 的 Bailian 预设也得到相同结论，测试配置未保存。切换到用户随后提供的新工作空间凭证后，SDK 文本、视觉直连和后端 HTTP 调用均成功，证明项目实现、网络和模型名称可用；旧凭证自身不可用。

真实图片排查：2358×1279、Display P3 JPEG 在 12 秒及临时 30 秒直连上限内均超时；等比例转换为 1280×694、sRGB、去元数据后约 22.4 秒成功。后端现会在内存中自动规范化长边超过 1280 像素或带 ICC 配置的图片，并将视觉调用超时调整为 30 秒。使用原始文件路径完成 HTTP 回归调用约 20.4 秒，识别为户外草地、多人、天空与白色衣物亮区、无彩色光。原图未被修改。失败汇总不再把全 null 字段标记为稳定。

API v1 候选协议现为 `1.0.0-rc3`：新增百炼多阶段意图解析和
`uncertainty_details`，并保留 rc2 `uncertainty` 字符串用于安全兼容。Pydantic、模型输入、
OpenAPI、Android Mapper、Mock 行为、README、冒烟和视频脚本已同步。

Android P0 已实现固定意图、BT.709 指标、单步 EV 策略、3/5 帧时序、低频语义缓存、
SafetyGuard、FakeCameraAdapter、写后读回模型和演示界面。Fake 能力标记为 Mock 并由
SafetyGuard 拒绝执行。该结果证明本地逻辑和 APK 可构建，不证明 Insta360 真机可写。

Android Lint 未计入通过项：`lintDebug` 所需的 `intellij-core-32.4.0.jar` 和
`kotlin-compiler-32.4.0.jar` 尚未下载到 Gradle 缓存，在线下载长时间无进展，离线运行明确
报告缺少这两个工具依赖。编译、17 项单元测试、APK 安装和模拟器运行均独立通过。

视频抽帧验收：新增 `scripts.video_probe`，依赖本机 FFmpeg，对本地视频均匀抽取 1–10 个临时 JPEG，顺序调用现有单帧接口，并输出每帧时间戳、协议有效性、枚举、原因、延迟和相邻成功帧的字段变化。整段视频不会上传，临时帧退出时删除，`test-results/` 已忽略。使用 2 秒无个人内容的合成测试视频完成一次真实百炼端到端调用，结果为 `other / display / display / true`，HTTP 200、`status=ok`、约 5.8 秒。

测试有两条来自 Starlette/AnyIO 的弃用提示（测试客户端 HTTPX 兼容入口与 BlockingPortal 别名），不影响当前通过结果；后续升级测试依赖时应检查迁移说明。未屏蔽这些提示。

## 仍需真实环境验收

- 自有真实照片连续调用 3 次：单次真实照片已成功并检查语义，仍需连续 3 次确认字段稳定性。
- B/C 对接确认：rc3 类型、字段级降级和融合仲裁已在 Android/后端实现，仍需团队在实际
  工作分支用真实服务完成联调。
- Android 到后端到百炼的完整链路：已有 rc3 客户端和模拟器入口，尚未接入真实相机预览
  帧并做真机端到端调用。
- 真实相机帧质量、SDK 操作和实际 EV 回读：属于 A/C 真机链路，后端测试无法替代。

这些未验证项不应写成 PASS。按 README 中的图片与 HTTP 冒烟命令完成真实照片和 Android 联调后，将耗时、状态及稳定性观察补充到此记录；不要记录密钥或原始私密图片。
