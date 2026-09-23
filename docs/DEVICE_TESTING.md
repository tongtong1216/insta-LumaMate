# 模拟器与华为真机验证

Android 模拟器运行在电脑上，适合验证 APK 安装、界面启动和基础运行。它不能模拟华为系统，也不能证明 USB 线、手机调试授权、华为系统兼容性或真实相机 SDK 已经可用。真机必须单独连接并测试。

## 本机模拟器

设备名：`LightPilot_API_37`。系统：Google APIs Android 17 / API 37，ARM64，适用于这台 Apple Silicon Mac。

在 Android Studio 的 Device Manager 中选择该设备并启动，然后点击项目 Run。也可在终端启动：

```sh
"$HOME/Library/Android/sdk/emulator/emulator" -avd LightPilot_API_37
```

已有 Android Studio 安装了模拟器程序，首次配置还需要下载系统镜像。模拟器文件位于本机 `~/.android/avd/`，不提交仓库。

### 本次实测记录（2026-09-22）

- `LightPilot_API_37` 已创建并启动，ADB 标识为 `emulator-5554`，系统 API 37、`arm64-v8a`。
- `assembleDebug` 与 `assembleDebugAndroidTest` 构建成功。
- 应用与测试 APK 安装均返回 `Success`，现有设备测试返回 `OK (1 test)`。
- 2026-09-23：rc2 Debug APK 安装成功，`MainActivity` 启动返回 `Status: ok`，界面结构
  确认显示 LightPilot P0 意图、指标和建议控件。
- 默认平衡意图与测试指标连续提交三帧后显示
  `EV_ONE_STEP_UP/target=1.0`；过期建议被 SafetyGuard 以 `EXPIRED` 拦截。
- 从模拟器访问 `http://10.0.2.2:8000/health` 返回 HTTP 200，后端仍为 Mock 模式。
- 最后一次设备检查仅出现模拟器，没有华为真机；尚不能确认华为手机的连接与运行兼容性。

现有设备测试仅验证应用上下文包名，不代表相机、业务界面或后端调用已完成。首次启动时模拟器因可用内存不足选择软件图形渲染，冷启动可能较慢。

## 连接华为真机

先在手机的“设置 → 关于手机”确认型号与系统版本。本仓库生成的是 Android APK，最低 Android API 24；华为具体系统是否支持 APK 与 ADB，需要结合机型、版本及实测确认。原生鸿蒙应用的开发与调试流程不能直接等同于 Android APK。

对于支持 Android/ADB 调试的机型：

1. 用支持数据传输的 USB 线连接手机与 Mac，保持手机解锁。
2. 在“关于手机”中多次点击版本号以开启开发人员选项。
3. 在开发人员选项中打开 USB 调试，USB 连接模式选择传输文件。
4. 在手机弹窗中允许这台电脑进行 USB 调试。
5. 运行下方命令，确认设备状态为 `device`。macOS 通常不需要 Windows 的 Google USB 驱动。

```sh
"$HOME/Library/Android/sdk/platform-tools/adb" devices -l
```

| 输出 | 含义与下一步 |
| --- | --- |
| 非 `emulator-` 开头的设备，状态 `device` | 真机 ADB 已连接并授权，可以继续安装验证 |
| `unauthorized` | 手机还没有授权这台电脑，解锁后确认弹窗 |
| `offline` | 当前连接不可用，重插数据线后再检查 |
| 只有 `emulator-5554` 等 | 仅连接了模拟器，真机尚未连接 |
| 列表为空 | 检查数据线、USB 模式、USB 调试及系统调试方式 |

华为特定机型的 USB 调试设置可参考[华为官方说明](https://consumer.huawei.com/cn/support/content/zh-cn00407281/)。具体菜单随系统版本变化。

## 安装并启动项目

在仓库根目录执行构建；同时连接多台设备时，务必指定设备序列号。

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug

# 将 DEVICE_SERIAL 替换为 adb devices 显示的实际序列号
"$HOME/Library/Android/sdk/platform-tools/adb" -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
"$HOME/Library/Android/sdk/platform-tools/adb" -s DEVICE_SERIAL shell am start -W -n com.example.insta_auto_adjust/.MainActivity
```

`install` 返回 `Success`，启动返回 `Status: ok`，手机上实际出现应用界面且没有崩溃，
才能确认该真机可以安装和运行当前 APK。当前项目已有多阶段策略界面和 rc3 后端客户端，
但尚未加入已验证的 Insta360 SDK，因此模拟器或普通手机安装成功不能证明相机连接与 EV 写入成功。

## 本地后端地址

后端运行在本机 8000 端口时，官方 Android 模拟器使用 `http://10.0.2.2:8000`。通过 USB 连接的 Android 真机可执行：

```sh
"$HOME/Library/Android/sdk/platform-tools/adb" -s DEVICE_SERIAL reverse tcp:8000 tcp:8000
```

随后真机可访问 `http://127.0.0.1:8000/health`。这只配置网络通道；App 仍需实现网络请求、INTERNET 权限和 debug HTTP 配置。模拟器访问后端成功不等于 App 已接入百炼或相机。
