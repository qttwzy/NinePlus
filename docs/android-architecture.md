# NinePlus Android 架构说明（第二轮整改后）

## 总览

```
UI (Compose)
  ↓ StateFlow / suspend
AppViewModel          ← structured concurrency，操作串行
  ↓
VehicleRepository
  ├── NinePlusApiClient   (HTTP + tolerant JSON)
  ├── AuthAssembler       (session 组装)
  ├── CredentialStore     (加密 bearer/session)
  ├── SettingsStore       (DataStore: URL、开关、缓存)
  ├── NinePlusDatabase    (Room: 行程 / 轨迹 / 历史)
  └── files/VehicleImages
```

## 认证模型（P0 已修复）

| 概念 | 位置 | 说明 |
|---|---|---|
| Server URL | DataStore（明文） | 非敏感；变更时清空 LoginResult |
| App Bearer | CredentialStore（加密） | `Authorization: Bearer …` |
| Session token | LoginResult → CredentialStore | 规范来源；组装进 `X-NinePlus-Session` |
| 有效配置 | `AuthAssembler.effectiveConfiguration` | 每次请求动态组装，App/Widget/Worker 一致 |

- logout 清 LoginResult → 后续请求无 session
- 进程重启从 CredentialStore 恢复 LoginResult
- 禁止把 session 存进 URL 配置 blob

## Widget 安全边界（P0 已修复）

| 组件 | exported | 可做什么 |
|---|---|---|
| `VehicleStatusWidgetReceiver` | true（系统要求） | 仅 `APPWIDGET_UPDATE` 渲染 |
| `WidgetActionReceiver` | **false** | 刷新、寻车铃 |
| `MainActivity` | true | 打开 App；危险操作需前台确认 |

**危险操作（上电/熄火/开座桶）绝不从 Widget 静默执行。**

## 车辆操作语义

Platform 端点是 `/engine/start|stop`，对应 `pwr`（电源）。
UI 使用「上电 / 熄火」，**不根据 `isLocked` 决定 engine 操作**。
`lock_status` / `loc.lock` 仅用于展示。

## RideRecorder

- 纯计算在 `RideMath`（可单测）：G 值、平滑、采样过滤
- **G 值 bug 已修复**：使用覆盖前的 `previousSpeed` 计算加速度
- 生命周期：`STOPPED / PREVIEWING / RECORDING`；离开页面 `stopPreview()`
- 持久化：`ActiveRideStore`（session 元数据 + JSONL 轨迹点 checkpoint）

## 地图

- MapLibre，坐标 GCJ-02 纠偏后上图
- 车辆位置地图：标记 + 相机
- 本地骑行轨迹地图：折线 + fit bounds + 起终点
- 服务器行程轨迹：依赖 raw 字段启发式解析，**未标为完整实现**

## 缓存 / JSON

- RideDetail raw 以 JSON **对象** 存储（修复二次 encode）
- 轨迹点 Room JSONL/checkpoint，非每秒整包重写
- Room **不再** destructive fallback

## 测试矩阵

| 类别 | 覆盖 |
|---|---|
| Auth/Network | MockWebServer：login→session header、logout、bearer+session、HTTP 错误、URL 构造、envelope |
| Parser | snake/camel/mixed prediction、login、travel、battery 字段 |
| RideMath | G 值、平滑、过滤、距离推算、静止门限 |
| Serialization | JSON round-trip、track point、login session |
| Coordinate/Dates | GCJ-02、月份/日期解析 |

## 与 iOS 差异（有意为之）

| 语义 | iOS | Android |
|---|---|---|
| 桌面组件 | WidgetKit 时间线 | AppWidget + 用户/定时刷新 |
| 充电进行中 | Live Activity | 常驻通知 |
| 后台刷新 | BGTaskScheduler | WorkManager |
| 危险 Widget 操作 | ControlWidget + 鉴权 | 打开 App 前台确认 |
| 凭据存储 | App Group UserDefaults | EncryptedSharedPreferences |
