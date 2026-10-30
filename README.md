# 定时打卡 / auto_attendance

这是“定时打卡”Android 应用及其 Magisk 模块的源码项目。模块将应用、FakeWifi APK 和开机脚本打包在一起。已构建的安装包位于 [`dist/auto_attendance-magisk-v2.5.0.zip`](dist/auto_attendance-magisk-v2.5.0.zip)。**ZIP 内不包含 README**；本文档只放在项目根目录。

## 功能

- 分别配置两个精确到秒的时段，默认早间 `09:00:00–09:29:59` 启用，晚间 `21:00:00–21:29:59` 禁用。可单独切换，也可一键重置默认配置；修改后立即重新登记闹钟，无需重启。
- 每个启用时段当天随机选择首次打开时间。每轮先将亮度设为最低、亮屏并退出无密码锁屏，打开飞书 `com.ss.android.lark` 30 秒；关闭飞书后等待 30 秒，若仍在时段内则继续下一轮。已开始的一轮允许跨过时段终点。
- 应用通过 Android `AlarmManager` 调度，Magisk `service.sh` 在开机后发送调度广播，并通过 Vector CLI 启用 FakeWifi、将飞书加入其作用域。
- 应用只负责打开和关闭飞书，**不会点击飞书内的考勤按钮**。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `src/`、`AndroidManifest.xml` | 定时打卡应用源码 |
| `build.sh` | 编译并签名应用 APK |
| `build_module.sh` | 构建 Magisk ZIP，不包含 README |
| `magisk_module/module.prop` | Magisk 模块信息 |
| `magisk_module/service.sh` | 后台开机脚本 |
| `magisk_module/config.sh` | 脚本参数 |
| `magisk_module/customize.sh` | Magisk 安装检查及权限设置 |
| `magisk_module/system/app/` | 打卡应用与 FakeWifi APK，供 Magisk 挂载为系统应用 |
| `dist/` | 已打包的 Magisk ZIP |

FakeWifi APK 是从已连接设备只读复制的 `com.hook.fakewifi` 1.0，保留原签名，带有 `testOnly` 标记；本项目没有 FakeWifi 的原始源码。其 SHA-256 为 `28988ef7b38b62485cf4119d6fd463e9d9cbf7c58e6b9a508eff11fab13544a3`。打卡应用源码及模块脚本均在本仓库中。

## 本地构建

需要 JDK（含 `javac`、`jar`、`keytool`）、Python 3、Android SDK Platform 30 与 Build Tools 35.0.0。默认读取 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`；未设置时使用 macOS 默认路径 `~/Library/Android/sdk`。可用 `BUILD_TOOLS_VERSION`、`ANDROID_PLATFORM` 和 `AUTOLARK_KEYSTORE` 覆盖相应路径。

```sh
sh build.sh
sh build_module.sh
```

`build.sh` 输出 `build/autolark.apk`；`build_module.sh` 会先编译应用，再输出 `dist/auto_attendance-magisk-v2.5.0.zip`。默认使用 `~/.android/debug.keystore`，不存在时会创建。若要更新设备上已安装的同包名应用，须继续使用**相同签名密钥**。

## 安装与配置

设备需要 Magisk、已启用的 Zygisk 和 Vector、飞书以及 Android 8.0 或更新版本。Vector 不包含在本 ZIP 中；缺少 Vector 时定时任务仍可调度，但 FakeWifi 不会注入飞书。

准备使用时，在 Magisk 中选择 ZIP 安装并重启，随后给“定时打卡”授予超级用户权限。在应用中设置时间和开关；`magisk_module/config.sh` 只控制开机等待次数及是否自动配置 FakeWifi，修改后在下次开机生效。开机日志位于 `/data/adb/codex-autolark/boot.log`。

若设备已有独立安装的这两个 APK，或仍有旧 `/data/adb/service.d/codex-autolark.sh`，迁移前应检查签名、版本和旧脚本，避免冲突及重复广播。停用模块不会自动删除原先独立安装的应用及其数据。此 ZIP 已做离线构建和结构校验，尚未在设备上安装验证。
