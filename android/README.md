# NinePlus Android

原生 Android 客户端，对接 **NinePlus Platform**（server-only 架构）。

> **状态（第二轮整改后）**：核心链路（认证、Dashboard、车控、行程、本地记录、Widget）已实现并通过单元测试。
> 真机/真车联调前请先跑 [DEVICE_TEST_PLAN.md](DEVICE_TEST_PLAN.md)。

## 技术栈

| 层 | 选型 |
|---|---|
| UI | Kotlin + Jetpack Compose + Material 3 |
| 异步 | Coroutines + StateFlow |
| 网络 | OkHttp + 轻量 API client |
| JSON | kotlinx.serialization + `PayloadParser` 兼容层 |
| 配置 | DataStore（URL）+ EncryptedSharedPreferences（凭证） |
| 业务数据 | Room |
| 地图 | 高德 Android Map SDK（矢量，GCJ-02 纠偏内置） |
| 后台 | WorkManager + Foreground Service |
| 推送 | FCM（可选，见下） |
| 桌面组件 | AppWidgetProvider |

最低 Android 8.0（API 26），target 35。

## 构建

```bash
cd android
./gradlew assembleDebug          # 调试包（允许 LAN HTTP）
./gradlew testDebugUnitTest      # 单元测试
./gradlew assembleRelease        # 发布包（无 keystore 时为未签名）
```

需要 JDK 17，Android SDK Platform 35。

### 地图与模拟器

地图使用高德 3D SDK（`android/local.properties` 的 `amap.key`，已 gitignore）。高德 GL 在
**host GPU（Apple Silicon Metal 转译）** 下会 `createContext failed` 并杀进程。模拟器请使用软件 GL：

- Android Studio：Device Manager → 编辑 AVD → Show Advanced Settings → **Graphics = Software - GLES 2.0 / 3.0**（等价 `hw.gpu.mode=swiftshader_indirect`）
- 或启动参数：`emulator -avd <name> -gpu swiftshader_indirect -no-snapshot-load`
- 改完必须**冷启动**（快照会让旧 GPU 模式继续生效）

真机不受影响。冒烟：`./gradlew connectedDebugAndroidTest`（`AmapGlSmokeTest`）。

### Release 签名

**不会**自动回退到 debug 签名。通过环境变量提供：

```bash
export NINEPLUS_KEYSTORE_PATH=/path/to/release.jks
export NINEPLUS_KEYSTORE_PASSWORD=...
export NINEPLUS_KEY_ALIAS=...
export NINEPLUS_KEY_PASSWORD=...
./gradlew assembleRelease
```

未配置时产物为 unsigned，需自行 `apksigner` 签名。

## 网络与 cleartext

| 构建类型 | HTTP 明文 |
|---|---|
| debug | 允许（便于本地 `http://192.168.x.x:19009`） |
| release | **拒绝**，仅 HTTPS |

配置见 `src/debug/res/xml/network_security_config.xml` 与 `src/release/...`。

## 认证

- Session token 规范来源是登录结果，经 `AuthAssembler` 注入 `X-NinePlus-Session`
- App Bearer 与 session 可并存：`Authorization: Bearer …` + `X-NinePlus-Session`
- 登出清 session；改服务器地址清 session
- 凭证存 EncryptedSharedPreferences

## 功能对照（诚实状态）

| 能力 | 状态 | 说明 |
|---|---|---|
| Dashboard 电量/续航/充电/状态 | 🟡 | 已实现，需真车验证 |
| 多车切换 | 🟡 | 已实现 |
| 寻车铃 | 🟡 | 已实现 |
| 上电 / 熄火 | 🟡 | 语义为电源（`pwr`），非锁车 |
| 开座桶 | 🟡 | 已实现，危险操作有确认 |
| 登录 / Session | ✅ | 含服务器切换失效 + MockWebServer 测试 |
| 行程列表 / 月份归档 | 🟡 | 已实现 |
| 行程详情 | 🟡 | 字段展示完整 |
| 服务器行程轨迹地图 | ❌ | Platform 无稳定 track contract，**未实现** |
| 本地骑行记录 | 🟡 | G 值/恢复/职责分离已修 |
| 本地轨迹地图 | 🟡 | 折线 + 起终点 + fit bounds |
| 车辆位置地图 | 🟡 | MapLibre GeoJSON 标记；默认 WGS-84 底图不转换 |
| Widget 刷新/寻车 | 🟡 | 安全边界已加固 |
| Widget 危险操作 | ✅ | 不静默执行；一次性确认 Dialog（可关闭） |
| 充电通知 | 🟡 | 本地驱动，不依赖 FCM |
| FCM 远程推送 | 🟠 | 客户端管道就绪，**需 Platform + google-services.json** |
| App Shortcuts / 语音 | ❌ | **未实现**（设置页已改文案） |
| 截图保护 | 🟠 | UI 遮罩开关，非系统级 FLAG_SECURE |
| 电池化学设置 UI | 🟠 | API 已通，完整设置面板待做 |

图例：✅ 实现+测试 · 🟡 实现待真机验证 · 🟠 部分 · ❌ 未实现

## 地图与坐标

- Style URI 显式配置（MapLibre 11.x 无 `streets` predefined style）
- 默认 MapLibre/OSM 底图使用 **WGS-84**，不做 GCJ-02 转换
- 若换用国内 GCJ-02 瓦片，将 `MapProviderConfig.needsGcj02` 设为 `true`
- 转换唯一入口：`MapProviderConfig.toMapCoordinate`

## 权限

| 权限 | 用途 |
|---|---|
| INTERNET | 访问 NinePlus Platform |
| ACCESS_FINE/COARSE_LOCATION | 车辆位置、骑行记录 |
| FOREGROUND_SERVICE_LOCATION | 骑行记录前台服务 |
| POST_NOTIFICATIONS | 充电/记录通知（Android 13+ 会真实请求） |

## 推送（真实状态）

```
client plumbing available / server support required
```

- 充电常驻通知：**不依赖 FCM**，刷新车况后自动起停
- 远程推送：需要
  1. Firebase 项目 + `android/app/google-services.json`
  2. `google-services` Gradle 插件
  3. NinePlus Platform 支持 Android FCM 注册与下发

## 真机验证

见 [DEVICE_TEST_PLAN.md](DEVICE_TEST_PLAN.md)。

## 架构

见 [docs/android-architecture.md](../docs/android-architecture.md)。
