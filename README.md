# Insta-auto_adjust

Android 端的 LightPilot 原型项目。用户在 App 内完成相机连接、实时预览、拍摄意图输入、建议确认与执行结果查看；相机控制由 Insta360 SDK 负责。

当前分支已合入 C/D 的 rc3 三阶段策略、场景语义后端和测试代码，但最新 UI 尚未调用该策略入口。真实预览继续由 A 的已验证 SDK 渲染链路负责；C 的取帧与策略执行需要在单一预览流和真实相机适配器完成后再接入。

## 首次运行

1. 使用 Android Studio 打开项目根目录。
2. 在 **Settings > Build Tools > Gradle** 中选择 **Embedded JDK**。
3. 等待 Gradle Sync 成功。
4. 连接已开启 USB 调试的 Android 真机，或选择模拟器。
5. 点击 Run，确认 App 能安装启动。

详细的团队环境要求见 [docs/ENVIRONMENT.md](docs/ENVIRONMENT.md)。

## 后端

FastAPI 后端位于 [lightpilot-backend/](lightpilot-backend/README.md)，提供 `/health`、
`/api/v1/parse-intent` 与 `/api/v1/analyze-scene`。默认 Mock 模式可联调；配置百炼后可
解析真实用户意图并分析图片。

Android 与后端使用 `1.0.0-rc3` 候选协议。完整的候选组件、SDK 接入点和验收步骤见
[docs/P0_IMPLEMENTATION.md](docs/P0_IMPLEMENTATION.md)。

D 与 C 开始联调时使用 [docs/D_C_INTEGRATION_GUIDE.md](docs/D_C_INTEGRATION_GUIDE.md)，
其中包含真实视频调用结果、请求/响应、缓存、HOLD、安全规则和完成标准。

## 约定

- Android 第三方依赖的版本统一维护在 `gradle/libs.versions.toml`；后端依赖锁定在 `lightpilot-backend/requirements*.txt`。
- 使用 `gradlew` / `gradlew.bat` 构建，不安装或提交个人 Gradle 配置。
- 不提交 `local.properties`、API Key、签名文件、APK 或构建产物。
- Insta360 SDK 的版本、来源、相机型号与固件版本经团队确认后再接入。
