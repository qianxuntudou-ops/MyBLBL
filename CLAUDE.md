# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

MyBili is a third-party Android TV client for Bilibili (哔哩哔哩), written in Kotlin. It targets TV remote (D-pad) navigation and supports video playback with danmaku (bullet comments), live streaming, search, and user management.

## Build & Run

```bash
# Debug build
./gradlew assembleDebug

# Release build (minified, arm64-v8a + armeabi-v7a only)
./gradlew assembleRelease

# Build with renamed APK output (MyBili-v{version}-{buildType}.apk)
./gradlew assembleDebugRenamed
./gradlew assembleReleaseRenamed

# Run unit tests
./gradlew test

# Run instrumented tests
./gradlew connectedAndroidTest
```

**Java target:** 17 (source and runtime). **Kotlin JVM target:** 17.

## Architecture

Single-module Android app (`:app`) with MVVM architecture:

```
app/src/main/java/com/tutu/myblbl/
├── MyBLBLApplication.kt    # Application init, Koin setup
├── core/                    # Shared framework
│   ├── common/              # Cache, settings, logging, image loading
│   ├── model/               # Base model classes
│   ├── navigation/          # Navigation helpers
│   └── ui/                  # Base UI components
├── di/AppModule.kt          # Koin DI — all ViewModels, repositories, services
├── event/                   # AppEventHub — cross-component event bus
├── feature/                 # Feature packages (one per screen/domain)
│   ├── home/                # Home feed with recommendations
│   ├── category/            # Content categories
│   ├── dynamic/             # Dynamic feed
│   ├── live/                # Live streaming
│   ├── player/              # Video player + danmaku engine
│   ├── detail/              # Video detail page
│   ├── search/              # Search (T9 + full keyboard)
│   ├── series/              # Bangumi/series tracking
│   ├── favorite/            # User favorites
│   ├── settings/            # App settings
│   ├── me/                  # User profile
│   └── user/                # User auth (QR code login)
├── model/                   # API response/data models
├── network/
│   ├── api/ApiService.kt    # Retrofit interface for all Bilibili APIs
│   ├── http/                # OkHttp client, interceptors, cookie management
│   ├── security/            # WBI anti-spider signature
│   └── session/             # Auth token management and refresh
├── repository/              # Repository layer (data source abstraction)
└── ui/                      # Activities and fragments
```

**Data flow:** Network → Repository → ViewModel → UI (Fragments/Activities). Cross-component communication uses `AppEventHub`.

**DI:** All dependencies registered in `di/AppModule.kt` via Koin. ViewModels are injected per feature.

## Key Technical Details

- **Networking:** Single Retrofit `ApiService.kt` interface. OkHttp interceptors handle cookies, WBI signatures, and auth tokens. Uses Tencent Maven mirrors.
- **WBI Security:** `network/security/` implements Bilibili's anti-scraping parameter signing — required for most API calls.
- **Session/Auth:** QR code login flow. Session tokens stored and refreshed via `network/session/`.
- **Player:** Media3 ExoPlayer with adaptive streaming. Embedded Kuaishou AkDanmaku engine (Protobuf-based danmaku protocol).
- **TV Focus:** All UI is optimized for D-pad navigation. Focus management is critical — avoid breaking focus chains.
- **ProGuard:** Release builds use R8 with rules in `proguard-rules.pro`. Model, network, DI, ViewModel, and danmaku engine classes are kept.
- **View Binding:** Enabled — use view binding instead of `findViewById`.

## Conventions

- Package name: `com.tutu.myblbl`
- Feature modules are self-contained: each has its own ViewModel, Repository references, and UI fragments
- API responses use Gson with custom TypeAdapters in `model/adapter/`
- Settings use DataStore Preferences (not SharedPreferences)
- Lint is heavily suppressed (see `build.gradle.kts` lint block) — don't add new suppressions without reason

