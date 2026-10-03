# NineBot+

NineBot+ is a personal iOS and Android client for viewing and managing Ninebot vehicle status. Both clients use a **NinePlus Platform** server for the shared HTTP+JSON contract and provide dashboards, widgets, trip history, and local ride recording.

Telegram: https://t.me/ninebotultra

The `main` branch is the integrated project baseline for the iOS and Android clients. NinePlus Platform source lives in `platform/` and deploys independently; clients do not connect to Ninebot cloud directly.

## Clients

| Client | Path | Status |
|---|---|---|
| iOS (SwiftUI) | `mini-ninebot/` | Maintained |
| Android (Kotlin + Compose) | `android/` | In progress — core flows implemented |

## Features (both platforms)

- Vehicle dashboard with battery, estimated range, status, charging state, and location.
- Multi-vehicle switch.
- Vehicle controls: ring bell, open seat bucket, engine start/stop.
- Trip history, mileage trends, local ride recording.
- Vehicle location maps and coordinate handling appropriate to each client's map provider.
- Home screen widgets.
- Charging notifications / live status.
- Local cache with offline fallback.

## Repository layout

```
Ninebot-plus-plus/
├── mini-ninebot/          # iOS app + widgets
├── android/               # Android app (Jetpack Compose)
├── platform/              # NinePlus Platform (ninecli adapter + snapshot cache)
├── docs/                  # Architecture notes
└── README.md
```

## iOS build

See [mini-ninebot/README.md](mini-ninebot/README.md). Requires Xcode, Apple Developer signing, and a reachable NinePlus Platform server.

## Android build

See [android/README.md](android/README.md).

```bash
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

Requires JDK 17 and Android SDK 35. Architecture notes: [docs/android-architecture.md](docs/android-architecture.md).

## NinePlus Platform

The clients do **not** connect to Ninebot cloud directly. They call a NinePlus Platform that polls the vehicle cloud and exposes a stable HTTP+JSON API:

```
Client → NinePlus Platform → 九号云端 / 车辆
```

Typical endpoints used by clients:

- `POST /accounts/login`
- `GET /vehicles`, `GET /vehicles/{sn}/dashboard|status|battery|prediction`
- `GET|POST /vehicles/{sn}/travel*`
- `POST /vehicles/{sn}/bell|buck|engine/start|engine/stop`
- `POST /devices/register|unregister` and `POST /push/test` (Android FCM)

Clients send `Authorization: Bearer <app token>` (optional) and `X-NinePlus-Session` (after login).

## Setup (quick)

1. Build and install the client.
2. Open **我的 / Settings**.
3. Enter your NinePlus server address and optional App Bearer Token.
4. Bind your account (phone + password).
5. Refresh the dashboard.
6. Add widgets / enable charging notifications as needed.

## Privacy

Configuration, login state, vehicle snapshots, trip records, and local ride records stay on device. Do not commit personal tokens, account data, signing certificates, or build artifacts.

## License / distribution

Personal builds by default; not configured for App Store / Play Store distribution.
