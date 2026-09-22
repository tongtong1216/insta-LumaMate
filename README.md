# Insta-auto_adjust

Android 端的 LightPilot 原型项目。用户在 Android App 内完成相机连接、预览、拍摄意图输入、建议确认与执行结果查看；相机控制由设备 SDK 负责。

## 首次运行

1. 使用 Android Studio 打开项目根目录。
2. 在 **Settings > Build Tools > Gradle** 中选择 **Embedded JDK**。
3. 等待 Gradle Sync 成功。
4. 连接已开启 USB 调试的 Android 真机，或选择模拟器。
5. 点击 Run，确认空 App 能安装启动。

详细的团队环境要求见 [docs/ENVIRONMENT.md](docs/ENVIRONMENT.md)。

## 后端

FastAPI 后端位于 [lightpilot-backend/](lightpilot-backend/README.md)，提供 `/health` 与 `/api/v1/analyze-scene`。默认 Mock 模式可直接联调；配置百炼密钥、地址并切换模式后可分析真实图片。安装、启动、Android 连接方法和接口协议均见后端 README。

## 约定

- Android 第三方依赖的版本统一维护在 `gradle/libs.versions.toml`；后端依赖锁定在 `lightpilot-backend/requirements*.txt`。
- 使用 `gradlew` / `gradlew.bat` 构建，不安装或提交个人 Gradle 配置。
- 不提交 `local.properties`、API Key、签名文件、APK 或构建产物。
- Insta360 SDK 的版本、来源、相机型号与固件版本经团队确认后再接入。
