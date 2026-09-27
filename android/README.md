# NinePlus Android

原生 Android 客户端，对接 **NinePlus Platform**（server-only 架构，与 iOS 版一致）。

```
Android / iOS Client
        ↓
   HTTP + JSON
        ↓
NinePlus Platform
        ↓
九号云端 / 车辆
```

## 技术栈

| 层 | 选型 |
|---|---|
| UI | Kotlin + Jetpack Compose + Material 3 |
| 异步 | Coroutines + StateFlow |
| 网络 | OkHttp + 自研轻量 API client |
| JSON | kotlinx.serialization + 兼容解析层 `PayloadParser` |
| 本地配置 | DataStore Preferences |
| 本地业务数据 | Room（行程、轨迹点、历史快照） |
| 地图 | MapLibre GL（开源，GCJ-02 纠偏内置） |
| 后台 | WorkManager + Foreground Service（骑行记录） |
| 推送 | FCM（可选，无 google-services.json 也可构建） |
| 桌面组件 | AppWidgetProvider + RemoteViews |

最低 Android 8.0（API 26），target 35。

## 构建

```bash
cd android
./gradlew assembleDebug          # 调试包
./gradlew testDebugUnitTest      # 单元测试
./gradlew assembleRelease        # 发布包（需签名配置）
```

需要 JDK 17，Android SDK Platform 35。

### Release 签名

通过环境变量提供，**不要**把 keystore / 密码提交进仓库：

```bash
export NINEPLUS_KEYSTORE_PATH=/path/to/release.jks
export NINEPLUS_KEYSTORE_PASSWORD=...
export NINEPLUS_KEY_ALIAS=...
export NINEPLUS_KEY_PASSWORD=...
./gradlew assembleRelease
```

## 配置

1. 安装后进入「我的」
2. 填写 NinePlus 服务器地址（例如 `http://192.168.1.10:19009`）
3. 可选：App Bearer Token
4. 手机号 + 密码登录（需勾选用户协议）
5. 回到「车控」刷新

## 功能对照（iOS ↔ Android）

| iOS | Android | 状态 |
|---|---|---|
| Dashboard 车况 | 车控 Tab | ✅ |
| 多车切换 | 切换车辆 Sheet | ✅ |
| 寻车铃 / 座桶 / 上电 / 熄火 | 动作面板 + 危险操作确认 | ✅ |
| 续航 / 电量 / 充电预测 | Hero + 电池卡 | ✅ |
| 行程列表 / 月份归档 | 行程 Tab | ✅ |
| 趋势分析 | 行程 Tab 趋势卡 | ✅（图表简化） |
| 行程详情 / 轨迹 | 行程详情 | ✅（轨迹图后续增强） |
| 本地骑行记录 | 记录 Tab + 前台服务 | ✅ |
| WidgetKit 桌面组件 | AppWidget | ✅ |
| Live Activity 充电 | 常驻充电通知 | ✅ |
| Siri / App Intents | App Shortcuts + Deep Link | 部分（launcher shortcuts 可扩展） |
| BGTaskScheduler | WorkManager | ✅ |
| MapKit | MapLibre + Geocoder | ✅ |
| 截图保护 | FLAG/隐私遮罩开关 | 部分 |
| App Group 共享缓存 | DataStore + Room + files | ✅ |

## 权限

| 权限 | 用途 |
|---|---|
| INTERNET | 访问 NinePlus Platform |
| ACCESS_FINE/COARSE_LOCATION | 车辆位置展示、本地骑行记录 |
| FOREGROUND_SERVICE_LOCATION | 骑行记录前台服务 |
| POST_NOTIFICATIONS | 充电通知、记录状态 |

## 地图与坐标

车辆 GPS 为 WGS-84。`CoordinateTransform` 将坐标转为 GCJ-02 后上图（与 iOS `NinebotCoordinateTransform` 一致），适配国内地图瓦片。逆地理使用 Android `Geocoder`。

## 推送

- 本地充电通知不依赖 FCM，刷新车况后自动起停。
- 若需远程推送（服务器主动通知）：
  1. 在 Firebase 控制台创建 Android 应用
  2. 放入 `android/app/google-services.json`
  3. 在 `android/app/build.gradle.kts` 应用 `com.google.gms.google-services` 插件
  4. NinePlus Platform 需支持 Android FCM device registration（`POST /devices/register` 已预留 `bundle_id`/`environment`）

## 已知限制

- 行程轨迹地图渲染为简化版（详情数据完整，地图绘制可继续增强）
- App Shortcuts 静态声明可再补齐完整 XML
- 截图保护目前为界面遮罩开关，未做系统级 `FLAG_SECURE` 全局切换
- 电池化学设置 UI 尚未从电池详情完整接入
- FCM 需自行提供 `google-services.json`

## 真机验证清单

1. 配置服务器 → 测试连接 → 登录
2. 刷新车况，确认电量/续航/位置
3. 多车切换
4. 寻车铃（低风险）；开锁/关锁/座桶（确认弹层）
5. 行程月份筛选与详情
6. 开始骑行记录 → 后台 → 结束 → 查看本地记录
7. 添加桌面小组件，点刷新
8. 充电时查看通知

## 架构

详见 [docs/android-architecture.md](../docs/android-architecture.md)。
