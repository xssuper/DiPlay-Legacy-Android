# Android Auto 接收器并入 DiPlay —— M0 可行性清单

方案 B（把 DiAuto 的接收器作为独立 Gradle 模块 `:autoreceiver` 并入）。本文是 M0 的产出：
DiAuto 的实际结构、六类冲突及每一项的处理决定、以及 DiPlay 与接收器之间的窄接口。

- 上游：`https://github.com/shihabal3amri/DiAuto`，并入基线 commit `f3046721814bff289a78e58f597b45d895efbb95`（0.3.12）
- 许可证：AGPL-3.0（DiAuto 本身是 Open Headunit 的分支）。DiPlay 界面部分已是 AGPL-3.0，方向一致。
- 决策（来自计划文档"需要你决策的问题"）：**老款车机（Android 4.4–9）要支持**；**安卓模式默认关闭**（实验性）。

## 1. DiAuto 的入口与接收器边界

| 组件 | 作用 | 并入后的处理 |
| --- | --- | --- |
| `App : Application` | 整个接收器的 DI 根，120 处 `App.provide(context)`，`get()` 里 `applicationContext as App` | 改为 `open class`，由 `DiPlayApplication` 继承；新增 `androidModeEnabled` 门控，关闭时 `onCreate` 发布实例后立即返回 |
| `.aap.AapService` | 前台服务，持有会话、解码、音频 | 保留，`exported=false`，由 DiPlay 经接口启动/停止 |
| `.aap.AapProjectionActivity` | 投影界面（SurfaceView + 触摸） | 保留，`exported=false` |
| `.app.UsbAttachedActivity` | USB 插入后的权限申请与 AOA 切换 | 保留类，**去掉 `USB_DEVICE_ATTACHED` intent-filter**：由 DiPlay 的 attach 处理决定是否进入安卓模式（否则用户选了"仅 CarPlay"也会被拉起） |
| `.main.MainActivity` | DiAuto 自己的主页/设置入口 | 保留类，**去掉 LAUNCHER/APP_MAPS intent-filter**，避免 APK 里出现第二个桌面图标 |
| `.main.SettingsActivity` 等 | DiAuto 设置界面 | 保留类但不导出。驱动的设置入口仍是 DiPlay 的设置页 |
| `.connection.UsbReceiver` / `CarKeyReceiver` / `RemoteControlReceiver` | USB 权限、方向盘键、媒体键 | 保留 |
| `.app.BootCompleteReceiver` / `AutoStartReceiver` / `WifiAutoStartReceiver` | 开机、蓝牙、Wi-Fi 自启 | 保留声明，但内部继续读 DiAuto 的自启设置；DiPlay 的"安卓模式"总开关关闭时不会到达这里 |
| `AutomationActivity` | 外部自动化入口（`headunit://`） | 保留，仅保留 action 式 intent-filter，去掉 `headunit://` 深链以避免与 DiPlay 自己的 URI 冲突 |

`AapProjectionActivity` 与 `AapService` 是唯一必须保留的两个组件；其余都是支撑件，可以后续再裁。

## 2. 六类冲突与处理决定

### 2.1 构建工具链

| 项 | DiPlay | DiAuto | 决定 |
| --- | --- | --- | --- |
| Gradle | 9.5.0 | 8.x wrapper | 用 DiPlay 的 wrapper |
| AGP | 9.3.0（内置 Kotlin 支持） | 8.13.2 | 用 DiPlay 的 AGP；`:autoreceiver` 只应用 `com.android.library`，不额外应用 kotlin 插件 |
| Kotlin | 2.2.10 | 1.9.22 | 跟随 AGP 9 内置 Kotlin。已知风险：K2 对部分 1.9 写法更严；出现编译错误时在模块内改写法，不改语义 |
| kapt | 未使用 | `lifecycle-compiler`、`glide-compiler` | **不用 kapt**。核查后 DiAuto 代码没有使用 GlideApp 生成 API，也没有 `@OnLifecycleEvent`；两者都可去掉 |
| AIDL | 未使用 | `IShizuku.aidl`（Shizuku） | `buildFeatures.aidl = true`，AIDL 文件随模块导入 |

### 2.2 SDK 版本

| 项 | DiPlay | DiAuto | 决定 |
| --- | --- | --- | --- |
| compileSdk | 37 | 36 | 37 |
| minSdk | 17 | 16（github）/21（playstore） | **17**。老款车机必须支持，因此不采用 21 |
| targetSdk | 37 | 36 | 37 |
| Java | 11 + core library desugaring | 8 | 11 + desugaring（接收器用了 `java.util.function`、`java.time` 一类 API） |

### 2.3 原生代码（最大风险）

DiAuto 有三个 `.so`：`usbhelper`（libusb）、`hur_soft_hevc`（ffmpeg HEVC 软解）、`local_hotspot_radio`（热点无线电），
由 CMake 3.22.1 + **NDK 29** 构建，且 `libusb1.0.so` 是预编译产物（16 KB 页对齐）。

NDK 26 起最低支持 API 21，与"必须支持 Android 4.4"直接冲突。

**决定：本阶段不构建任何 C++，模块不带 `.so`。**

- 三个 JNI 入口（`connection/UsbNative`、`connection/LegacyHotspotRadio`、`decoder/FfmpegHevcDecoder`）改为**惰性、可失败**的加载：`System.loadLibrary` 放进 `runCatching`，失败时 `isAvailable = false`。
- 缺少它们时的退化路径：USB 走 DiPlay 自己的 `usbdirect`/usbfs 实现；HEVC 走平台 `MediaCodec`（API 21+ 的硬解）；热点走 DiPlay 现有的热点后端。
- 后续若要软解 HEVC，再单独做一个 `minSdk 21` 的可选模块，老机型不带。

### 2.4 依赖

| 依赖 | DiAuto 用途 | 决定 |
| --- | --- | --- |
| `protobuf-java:3.25.1` | 协议消息（生成代码已入库，10.8 万行） | 保留。**不需要** protobuf gradle 插件，生成代码随源码导入 |
| `conscrypt-android:2.5.3` | Android 4.4/5.x 上的 TLS 1.2 | 保留（正是老车机需要的） |
| `play-services-nearby:19.3.0` | 无线发现 | **移除**。它声明 `minSdk 21`，与 Android 4.4 支持直接冲突（manifest merger 直接失败）。`connection/NearbyManager.kt` 改为同 API 的空实现（`isAvailable = false`），无线发现退化为 Wi-Fi Direct / 蓝牙 / 网络发现三条不依赖 Play services 的路径 |
| `shizuku:api/provider:13.1.5` + `libsu:core:6.0.0` | root/shell 热点修补 | **移除**。Shizuku 的 provider 声明 `minSdk 23`，与 Android 4.4 冲突；DiPlay 已有自己的 `LocalAdb` 后端承担同样的 shell 工作。`utils/SUExecutor.kt` 改为同 API 的空实现（无特权后端），上游只有两处调用（`App.onCreate` 的 `register` 与 FYT 方向盘的 `setProp`），都是可选增强 |
| `dexmaker:2.28.3` | `HotspotManager` 的运行时子类化 | 保留（minSdk 14，不与 17 冲突） |
| `glide:4.16.0` | 加载屏动图 | 保留，无注解处理器 |
| `zxing:core:3.5.3` | 配对二维码 | 保留 |
| `material` / `appcompat` / `activity-ktx` / `fragment-ktx` / `navigation-*` / `recyclerview` / `localbroadcastmanager` / `lifecycle-*` / `multidex` | UI 与生命周期 | 保留；DiPlay 已有同族依赖，版本取两者较高者，靠 Gradle 冲突解析统一 |
| jitpack | `libsu` | 在 `settings.gradle.kts` 的 `dependencyResolutionManagement` 增加 `https://jitpack.io`（仓库模式是 `FAIL_ON_PROJECT_REPOS`，只能加在这里） |

### 2.5 包名与资源

| 项 | 决定 |
| --- | --- |
| applicationId | 模块是 library，**没有** applicationId；最终仍是 `com.shihab.diplay` |
| namespace | `com.andrerinas.openheadunit`（保持上游源码不改包名，降低同步成本） |
| 资源名 | 不同 namespace，不与 DiPlay 冲突；DiAuto 的 910 条字符串随模块导入 |
| FileProvider authority | 改为 `com.shihab.diplay.autoreceiver.fileprovider`，避免与 DiPlay 自己的 FileProvider 撞车 |
| Shizuku authority | 改为 `com.shihab.diplay.autoreceiver.shizuku` |
| NAVIGATION_UPDATE 权限 | 改为 `com.shihab.diplay.autoreceiver.permission.NAVIGATION_UPDATE` |

### 2.6 Manifest 合并

- 库清单**不声明任何 `<application>` 属性**（`android:name`/icon/label/theme/backup 归 DiPlay 所有），否则合并失败。
- 去掉 Launcher 过滤器、`USB_DEVICE_ATTACHED` 过滤器、`headunit://` 深链。
- 权限取并集：DiPlay 已有的重复声明由 manifest merger 去重；新增的（`NEARBY_WIFI_DEVICES`、`BLUETOOTH_*`、`FOREGROUND_SERVICE_*`）保留，均带 `tools:targetApi`。
- `<queries>` 保留（Android 11+ 包可见性，比亚迪 HUD 相关包名）。

### 2.7 两边都有的能力（保留哪一个）

| 能力 | 决定 |
| --- | --- |
| 比亚迪 HUD 导航 | 保留 **DiPlay 的实现**（`:shared` 的 cluster/hud 路径，已在真机验证）。DiAuto 的 `hud/` 代码仍随模块导入以维持编译，但不注册、不启动 |
| 热点 | 保留 DiPlay 的热点后端；DiAuto 的热点代码只在它自己的无线流程里被动调用 |
| USB | 有线走 DiAuto 的 AOA 切换（这是它的强项）；DiPlay 的 `usbdirect` 仍是 iPhone 与 libusb 缺失时的兜底 |
| ADB | DiPlay 的 `LocalAdb` 为准，Shizuku 路径保留但不作为默认 |

## 3. 窄接口草案（已落地为代码）

`autoreceiver/src/main/java/com/shilapi/xcertplay/autoreceiver/AutoReceiver.kt`

```kotlin
interface AutoReceiver {
    enum class State { IDLE, STARTING, PROJECTING, ERROR }

    fun interface Listener {
        fun onStateChanged(state: State, detail: String?)
    }

    fun start(context: Context, device: UsbDevice? = null): Boolean
    fun stop(context: Context)
    fun state(context: Context): State
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
}
```

实现 `DiAutoReceiver` 只做三件事：把 `CommManager.connectionState` 映射成四个状态、把 start 转成
`UsbAttachedActivity`（有线）或 `AapService.ACTION_START_WIRELESS`（无线）、把 stop 转成
`AapService.ACTION_STOP_SERVICE`。DiPlay 不接触协议、解码器或会话状态机。

## 4. 许可证合规清单

- [x] 保留 DiAuto 每个文件的版权与 AGPL-3.0 头（随源码原样导入）
- [x] `autoreceiver/build.gradle.kts` 顶部注明来源、许可证与相对上游的差异
- [x] 保留上游 `COPYRIGHT_MICHAEL_REID_GPLv3AFFERO.txt`（见 `docs/licenses/`）
- [ ] 更新 `docs/THIRD_PARTY_NOTICES.md`：并入模块、来源仓库、并入 commit
- [ ] 逐个核对新增第三方库许可证（protobuf-java、Conscrypt、Glide、ZXing、Shizuku、DexMaker、libsu、play-services-nearby）
- [ ] README 增加"独立项目，未获 Google、Apple 或比亚迪认可"的声明

## 5. 尚未覆盖（下一里程碑）

- `Android 4.4–9` 上的 TLS/解码性能实测（M5 真机矩阵）
- 接收器状态与 `MirrorSessionCoordinator` 的接线（`START_ANDROID_AUTO` 决策 → `AutoReceiver.start`）
- DiPlay 设置页里"安卓模式"总开关（默认关闭）+ 7 语言字符串
- APK 体积与方法数记录（并入后首次 assembleRelease 时记入本文）
