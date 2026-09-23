# Android 环境配置与验证

本文用于让每位成员配置出与本项目一致的 Android 开发环境，并验证环境可用。

## 1. 项目统一版本

以下版本由项目文件锁定，请不要自行修改：

| 项目 | 统一值 |
| --- | --- |
| Android Studio | 2026.1 Stable |
| Gradle JVM criteria | Version 25（由 Android Studio 自动匹配 Embedded JDK） |
| Android SDK Platform | API 37 |
| Android SDK Build-Tools | 37.0.0 |
| Gradle Wrapper | 9.6.0 |
| Android Gradle Plugin | 9.4.0 |
| Kotlin | 2.2.10 |
| Compose BOM | 2026.02.01 |
| compileSdk / targetSdk | 37 / 37 |
| minSdk | 29（Insta360 GO Ultra SDK 2.1.5 接入基线） |

依赖版本以 `gradle/libs.versions.toml` 为准；Gradle 版本以 `gradle/wrapper/gradle-wrapper.properties` 为准。无需单独安装 Gradle。

## 2. 首次配置

### 2.1 安装 Android Studio

安装 Android Studio 2026.1 Stable。安装完成后启动 Android Studio，不需要另行安装 Java。

### 2.2 安装 Android SDK

打开 `File > Settings > Languages & Frameworks > Android SDK`。

在 **SDK Platforms** 中安装：

- `Android 17.0`，API Level `37.0`

在 **SDK Tools** 中安装：

- Android SDK Build-Tools `37.0.0`
- Android SDK Platform-Tools
- Android SDK Command-line Tools (latest)
- Android Emulator（仅需模拟器测试时安装）
- Google USB Driver（Windows 使用 USB 真机时建议安装）

不要安装 Canary、DEV Preview、beta 等预览版 SDK。

### 2.3 打开项目并确认 Gradle JVM

1. 克隆仓库后，用 Android Studio 打开项目根目录，而不是只打开 `app` 目录。
2. 打开 `File > Settings > Build, Execution, Deployment > Build Tools > Gradle`。
3. 在 **Gradle Projects** 中选择本项目，确认 **Distribution** 为 **Wrapper**。
4. 在 **Gradle JVM criteria** 中确认 **Version** 为 **25**；**Vendor** 保持 **Any vendor** 即可。

Android Studio 2026.1 会根据该条件自动使用匹配的 Embedded JDK，因此这个版本不再显示旧版的 **Gradle JDK** 下拉框。不要改用电脑命令行中的全局 Java 8。

`local.properties` 会由 Android Studio 自动写入本机 SDK 路径；每个人的路径不同，不需要修改或提交它。

### 2.4 macOS 终端环境

如果 Android Studio 安装在默认位置，在项目根目录的终端执行：

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

./gradlew :app:assembleDebug :app:testDebugUnitTest --console=plain
```

这些环境变量只影响当前终端，不会更改其他项目使用的全局 Java。新开终端后需重新执行以上 `export` 命令。SDK 安装在其他位置时，请同步调整 `ANDROID_HOME` 与 `local.properties` 中的 `sdk.dir`。

构建成功后，调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，单元测试报告位于 `app/build/reports/tests/testDebugUnitTest/index.html`。这两项验证不需要连接设备；安装启动与设备测试仍需要真机或已配置的模拟器。

## 3. 首次 Gradle Sync

打开项目后 Android Studio 会自动执行 Gradle Sync。若需要手动触发，按 `Ctrl + Shift + A`，搜索并执行 `Sync Project with Gradle Files`。

首次 Sync 会下载 Gradle、Android Gradle Plugin、Kotlin 和 Compose 依赖，耗时较长属正常现象。

### 无法下载 Gradle 的处理

若报错无法连接 `services.gradle.org`，先检查网络或 Android Studio 代理：

`File > Settings > Appearance & Behavior > System Settings > HTTP Proxy`

若浏览器能从官方地址下载 `gradle-9.6.0-bin.zip`，可临时使用本地 ZIP 完成首次 Sync：

1. 在 PowerShell 执行以下命令校验下载文件；结果必须与指定值一致。

   ```powershell
   Get-FileHash -Algorithm SHA256 "D:\你的下载目录\gradle-9.6.0-bin.zip"
   ```

   指定 SHA-256：

   ```text
   bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01
   ```

2. 临时编辑 `gradle/wrapper/gradle-wrapper.properties`，将 `distributionUrl` 改为自己的本地文件，例如：

   ```properties
   distributionUrl=file:///D:/download/gradle-9.6.0-bin.zip
   ```

3. 完成 Sync 后，必须恢复为仓库中的官方地址，且不要提交本机 `D:` 路径：

   ```properties
   distributionUrl=https\://services.gradle.org/distributions/gradle-9.6.0-bin.zip
   ```

## 4. 环境验证

按顺序完成以下两项即可确认环境配置成功。

### 4.1 验证 Gradle 构建

在 Android Studio 执行 Gradle Sync。Build 窗口最终显示以下文字即通过：

```text
BUILD SUCCESSFUL
```

这表示 Gradle、Embedded JDK、Android SDK Platform、Android Gradle Plugin、Kotlin 和项目依赖已可共同工作。

### 4.2 验证应用运行

1. 在顶部设备下拉框选择 Android 模拟器，或连接一部已开启 USB 调试的 Android 真机。
2. 点击绿色 Run 按钮。
3. App 能被安装并打开，且没有崩溃，即通过。

模拟器只用于确认 UI 和基础构建；相机连接、预览和 EV 调节必须使用真实 Android 手机与真实相机另行验证。

本机模拟器与华为手机的连接、安装及排查步骤见 [DEVICE_TESTING.md](DEVICE_TESTING.md)。

## 5. 通过标准

满足以下全部条件，即可认为本机环境与团队环境同步成功：

- Gradle JVM criteria 的 Version 为 25，Distribution 为 Wrapper。
- 已安装 API 37 与 Build-Tools 37.0.0。
- Gradle Sync 显示 `BUILD SUCCESSFUL`，没有红色错误。
- LightPilot P0 App 能在模拟器或真机启动。
- `gradle-wrapper.properties` 中没有任何个人本地磁盘路径。

## 6. Insta360 Android SDK 2.1.5 获取与团队配置

当前团队持有的 `SDK/Android-SDK-2.1.5/AndroidSDKDemo` 是官方 Demo 源码和可安装 APK，不是可直接提交或离线引用的 `sdk-camera` AAR 包。Demo 本身通过 Gradle 从影石官方 Maven 仓库解析：

```text
com.arashivision.sdk:sdk-camera:2.1.5
```

因此，成员首次构建主项目时必须能访问官方 Maven 仓库；Gradle 会将二进制包和传递依赖下载到该成员本机的用户级 Gradle 缓存，后续构建会复用缓存。

主项目已配置官方仓库地址，并从以下任一**本机私有**来源读取认证信息：

```text
%USERPROFILE%\.gradle\gradle.properties
```

```properties
insta360MavenUser=<官方提供的用户名>
insta360MavenPassword=<官方提供的密码>
```

或当前终端的环境变量：

```text
INSTA360_MAVEN_USER
INSTA360_MAVEN_PASSWORD
```

认证信息仅来自影石官方 SDK 包或团队安全渠道；不得写入项目文件、Git、APK、日志或聊天记录。`SDK/` 目录、Gradle 缓存和 AAR 文件也不得作为 Git 提交物。队友克隆项目后，应按相同方式配置自己的本机凭据并执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

若需要无网络构建或大量成员高速下载，应由团队在确认影石 SDK 许可允许后配置受控的内部 Maven 缓存；不要从 Demo APK 提取 AAR 作为依赖。

## 7. 已验证的本机真机预览环境（Windows）

以下配置已用于本项目的 Insta360 GO Ultra 实时预览真机验证。路径是当前开发机的示例；其他成员应按自己的安装位置调整，且不得提交 `local.properties` 或个人环境变量。

| 项目 | 已验证配置 |
| --- | --- |
| Android SDK | `D:\Android\Sdk` |
| ADB | `D:\Android\Sdk\platform-tools\adb.exe` |
| Android Studio Embedded JDK | `D:\Androir studio\jbr`（OpenJDK `25.0.3`） |
| Gradle 用户缓存 | `C:\Users\32529\.gradle` |
| 真机 | Android 10（API 29）或更高，已开启开发者选项与 USB 调试 |
| 相机 SDK | `com.arashivision.sdk:sdk-camera:2.1.5` |
| 媒体/预览 SDK | `com.arashivision.sdk:sdk-media:2.1.5` |

### 7.1 PowerShell 构建与安装

若终端没有自动识别 JDK 或 ADB，可在项目根目录使用以下命令。`JAVA_HOME` 和 `GRADLE_USER_HOME` 只在当前终端会话生效。

```powershell
$env:JAVA_HOME = 'D:\Androir studio\jbr'
$env:GRADLE_USER_HOME = 'C:\Users\32529\.gradle'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug

& 'D:\Android\Sdk\platform-tools\adb.exe' devices
& 'D:\Android\Sdk\platform-tools\adb.exe' install -r `
  '.\app\build\outputs\apk\debug\app-debug.apk'
```

`adb devices` 必须显示 `device`。若显示 `unauthorized`，请解锁手机并在“允许 USB 调试”对话框中允许当前电脑；建议勾选“始终允许”。若反复掉线，先在开发者选项中撤销 USB 调试授权，重新插拔数据线后再授权。

### 7.2 实时预览的 SDK 初始化要求

实时预览同时依赖相机 SDK 和媒体 SDK。应用在取得蓝牙权限后、开始扫描前必须完成以下初始化，缺少第二项会导致播放器 `prepare()` 阶段出现 `OffsetUtil.getLensType` 的 `UnsatisfiedLinkError`：

```kotlin
InstaCameraSDK.init(application) {
    cacheDir = application.externalCacheDir?.absolutePath
}
InstaMediaSDK.init(application)
```

预览播放器 `InstaCapturePlayerView` 必须绑定宿主 `Lifecycle`；否则可能已经执行 `prepare()` 与 `play()`，但始终不会进入 `onLoadingFinish`，因此无法绑定渲染管线或收到首帧。

真机验证流程：授予附近设备/蓝牙和附近 Wi-Fi 权限 → 扫描并选择 GO Ultra → 连接相机 Wi-Fi → 进入拍摄助手。预览区域只有在 SDK 的 `onFirstFrameRendered` 回调到达后，才可视为实时预览验证成功。

### 7.3 持久化诊断日志

相机连接与预览日志会写入手机的应用专属目录，可在 USB 临时断开后保留。日志不应记录相机 Wi-Fi 密码。

```text
/sdcard/Android/data/com.example.insta_auto_adjust/files/logs/camera/camera-runtime.log
```

日志自动轮转，保留最近 3 份、每份最多 512 KB。重新连接 USB 后可读取：

```powershell
& 'D:\Android\Sdk\platform-tools\adb.exe' shell cat `
  /sdcard/Android/data/com.example.insta_auto_adjust/files/logs/camera/camera-runtime.log
```

重点查看 `preview.player.prepareFailed`、`preview.pipeline.bound`、`preview.firstFrameRendered` 和 `preview.player.loadingTimeout` 事件。

## 8. C/D 后端本机测试环境（Windows）

当前开发机使用 `D:\Python\python.exe`（Python `3.13.5`）创建
`lightpilot-backend\.venv`。后端声明要求 Python 3.11 或更高；虚拟环境、`.env`、pytest
临时目录均不得提交。

```powershell
cd lightpilot-backend
& 'D:\Python\python.exe' -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-dev.txt
.\.venv\Scripts\python.exe -m pytest --basetemp=.pytest-tmp -p no:cacheprovider
.\.venv\Scripts\python.exe -m pip check
```

使用项目内 `--basetemp=.pytest-tmp` 可避免受限系统临时目录导致的 Windows `PermissionError`。
默认 `.env.example` 使用 Mock 模式，不会调用百炼；真实模型密钥仅可保存在
`lightpilot-backend\.env`，不得写入 Android 配置、日志或 Git。
