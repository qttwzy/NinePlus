# NinePlus Android 架构说明

## 总览

Android 客户端与 iOS 共享 **API Contract / Domain Semantics**，不共享 UI 源码。数据流：

```
UI (Compose)
  ↓ StateFlow / suspend
AppViewModel
  ↓
VehicleRepository
  ├── NinePlusApiClient   (HTTP + tolerant JSON)
  ├── SettingsStore       (DataStore: 配置 / 登录 / 小缓存)
  ├── NinePlusDatabase    (Room: 行程 / 轨迹 / 历史)
  └── files/VehicleImages (车辆图缓存)
```

## 包结构

```
com.example.ninebotplus/
  network/     JsonValue, PayloadParser, NinePlusApiClient
  domain/      纯领域模型（VehicleState, RideRecord, Dashboard…）
  data/        SettingsStore, Room, VehicleRepository
  ui/          Compose 界面 + AppViewModel
  location/    RideRecorder, RideRecordingService, CoordinateTransform
  push/        PushManager, FCM service
  widget/      AppWidgetProvider
```

## 关键决策

### 1. 兼容解析留在客户端一层

NinePlus Platform 转发的九号字段历史上 snake_case / camelCase 混用，并有嵌套 envelope。`PayloadParser` 集中做 tolerant parsing，并用 fixture 单测锁定行为。

**建议（服务端）**：逐步把 normalize 下沉到 Platform，输出版本化稳定 JSON。在那之前 Android 不另起一套 fragile parsing。

### 2. JSON 用 `JsonValue` 而不是强类型 data class

与 iOS `JSONValue` 对齐：脏字段只在解析层出现，ViewModel/UI 不接触原始 JSON。长期可由 OpenAPI 生成 DTO 替换。

### 3. 地图：MapLibre + GCJ-02

- 中国大陆可用，无 Google Play 依赖
- 开源，可自备瓦片
- `CoordinateTransform` 移植自 iOS，WGS-84 → GCJ-02
- 逆地理用系统 `Geocoder`（国产 ROM 通常走高德）

若未来要换高德 SDK，只需替换地图组件实现，不影响 domain。

### 4. 本地数据分层

| 数据 | 存储 | 原因 |
|---|---|---|
| 服务器配置 / 登录态 | DataStore | 小、高频读写 |
| 骑行记录 + 轨迹点 | Room | 结构化、可查询、可迁移 |
| 接口行程归档 | Room | 与本地记录可关联 |
| 历史快照 | Room | 趋势图 |
| 车辆图片 | files | 大二进制 |
| Dashboard 离线缓存 | DataStore (JSON) | 启动秒开 |

### 5. 骑行记录

- UI 启动 `RideRecorder`（预览）
- 开始记录时启动 `RideRecordingService`（`foregroundServiceType="location"`）
- 采样：`LocationManager` GPS，distanceFilter 1m
- 过滤：accuracy > 60m 丢弃；速度优先系统 speed，否则两点推算
- 平滑：EMA + 加速度限幅
- 距离：按轨迹点重算（`recalculatedDistanceMeters`）优先
- **不用**永久 wake lock / 激进轮询

### 6. 危险操作

开锁 / 关锁 / 开座桶在 UI 内走确认对话框；桌面 Widget 的对应按钮会触发系统级 PendingIntent，建议在产品层继续收敛（例如仅保留寻车）。

### 7. 推送

- 充电通知由 dashboard 同步驱动，不依赖 FCM
- FCM 可选；`NinePlusFirebaseMessagingService` 在无 `google-services.json` 时无副作用
- Platform 侧 push schema 建议统一：`title/body/vehicle_sn`，APNs 与 FCM 共用

### 8. 缓存 / 离线

- 启动加载 dashboard 缓存 → 立即可渲染
- 失败时保留上次数据 + 错误横幅
- 地址解析 15 分钟内同坐标复用

## 测试

`app/src/test` 覆盖：

- envelope 解包
- 字段别名（snake/camel）
- 车况/电池解析
- 坐标纠偏
- 月份与日期解析
- 行程时长解析

建议后续补：ViewModel 状态机、Repository 与 MockWebServer 集成测试。

## 与 iOS 差异（有意为之）

| 语义 | iOS | Android |
|---|---|---|
| 桌面组件 | WidgetKit 时间线 | AppWidget + 用户/定时刷新 |
| 充电进行中 | Live Activity | 常驻通知 |
| 后台刷新 | BGTaskScheduler | WorkManager |
| 逆地理 | MapKit / Apple | Android Geocoder |
| 共享容器 | App Group | DataStore + Room |
| 快捷指令 | App Intents | Deep Link + Widget 动作 |
