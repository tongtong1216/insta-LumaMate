# Insta-auto_adjust

Android 端的 LightPilot 原型。现已包含百炼多阶段意图解析、三个独立权重、BT.709
画面指标、字段级语义降级、低频场景缓存、单动作融合仲裁、3/5 帧时序确认、
SafetyGuard、Fake 相机适配器和建议确认界面。
Fake 相机默认标记为 Mock，不能绕过安全检查写入设备；真实相机控制仍由 A 通过
`CameraAdapter` 接入 Insta360 SDK。

三阶段意图规划的当前实现范围见
[docs/THREE_STAGE_PLANNING.md](docs/THREE_STAGE_PLANNING.md)：阶段一具备真实执行契约，阶段二
（快门/ISO）和阶段三（白平衡）已完成 Mock 策略、时序与安全拦截，等待 A 的真机能力快照。

## 首次运行

1. 使用 Android Studio 打开项目根目录。
2. 在 **Settings > Build Tools > Gradle** 中选择 **Embedded JDK**。
3. 等待 Gradle Sync 成功。
4. 连接已开启 USB 调试的 Android 真机，或选择模拟器。
5. 点击 Run，确认 LightPilot P0 演示页能安装启动。

详细的团队环境要求见 [docs/ENVIRONMENT.md](docs/ENVIRONMENT.md)。

## 后端

FastAPI 后端位于 [lightpilot-backend/](lightpilot-backend/README.md)，提供 `/health`、
`/api/v1/parse-intent` 与 `/api/v1/analyze-scene`。默认 Mock 模式可联调；配置百炼后可
解析真实用户意图并分析图片。

Android 与后端使用 `1.0.0-rc3` 候选协议。完整的 P0 组件、真实 SDK 接入点和验收步骤见
[docs/P0_IMPLEMENTATION.md](docs/P0_IMPLEMENTATION.md)。

D 与 C 开始联调时使用 [docs/D_C_INTEGRATION_GUIDE.md](docs/D_C_INTEGRATION_GUIDE.md)，
其中包含真实视频调用结果、请求/响应、缓存、HOLD、安全规则和完成标准。

## 约定

- Android 第三方依赖的版本统一维护在 `gradle/libs.versions.toml`；后端依赖锁定在 `lightpilot-backend/requirements*.txt`。
- 使用 `gradlew` / `gradlew.bat` 构建，不安装或提交个人 Gradle 配置。
- 不提交 `local.properties`、API Key、签名文件、APK 或构建产物。
- Insta360 SDK 的版本、来源、相机型号与固件版本经团队确认后再接入。
