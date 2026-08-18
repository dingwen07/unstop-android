# Unstop

Unstop is an Android utility that helps keep selected Firebase Cloud Messaging (FCM) apps eligible to receive messages when an OEM or Android profile has placed them in the stopped state.

The app uses [Shizuku](https://shizuku.rikka.app/) for shell-level package access. It discovers FCM-capable apps, lets you select monitored Android users and packages, and can periodically run the unstop check without launching the target apps.

## Requirements

- Android Studio with an Android SDK that includes API 37
- JDK 17
- A device or emulator with Shizuku installed and running
- Shizuku permission granted to Unstop

## Build and test

On macOS/Linux/WSL:

```shell
./gradlew test
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

Install and run the debug build from Android Studio, then start Shizuku and grant Unstop access. Select the users and FCM apps that should be monitored before enabling periodic protection.

## Project details

- Application ID: `net.extrawdw.apps.unstop`
- Minimum SDK: 36
- Target/compile SDK: 37
- UI: Jetpack Compose with Material 3
- Privileged package operations: Shizuku UserService
