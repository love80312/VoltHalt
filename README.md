<div align="center">

# ⚡ VoltHalt

### Smart Battery Charge & Low Battery Alarm for Android

**Set charge and low-battery alerts, choose your alarm sound, and keep control of your battery.**

[![Build](https://img.shields.io/github/actions/workflow/status/love80312/VoltHalt/android-apk.yml?branch=test_fix&label=Build%20(test_fix)&logo=github)](https://github.com/love80312/VoltHalt/actions/workflows/android-apk.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-blueviolet?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-blue?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Network permission](https://img.shields.io/badge/Internet%20permission-Not%20requested-success)](https://developer.android.com/guide/topics/permissions/overview)

</div>

---

## What is VoltHalt?

**VoltHalt** is an open-source Android app that monitors battery level while its monitoring service is enabled. It can alert you when the battery reaches a selected charging limit or falls below a low-battery threshold. Alerts can use a system alarm ringtone, an audio file selected by the user, or a spoken message generated with Android Text-to-Speech (TTS).

VoltHalt supports Android 8.0 (API 26) and later and does not require root access.

## Features

| Feature | Description |
|---|---|
| **Charging limit alarm** | Set a target from **10% to 100%**. The alarm triggers when the device is charging and reaches the selected level. |
| **Low-battery alarm** | Set a threshold from **5% to 50%**. The alarm triggers when the device is not charging and the battery reaches or falls below that level. |
| **Independent alarm settings** | Configure the sound, volume, and vibration separately for the charging-limit and low-battery alarms. |
| **System ringtone** | Choose an alarm sound through Android's system ringtone picker. |
| **Custom audio file** | Select an audio file through Android's document picker. VoltHalt requests access only to the selected file and stores its persistable read permission when the provider supports it. |
| **Text-to-Speech** | Use a spoken message instead of a ringtone. The voice, speech rate, pitch, and preferred engine are configured through Android's TTS settings. |
| **Quick Settings tile** | Toggle the **charging-limit alarm** from Android's Quick Settings panel. The tile reflects the charging-limit alarm setting; the low-battery alarm is controlled separately in the app. |
| **Full-screen alarm screen** | Show an alarm screen over the lock screen when Android permits the full-screen notification. |
| **Automatic charging-alarm stop** | The charging-limit alarm stops when the device is no longer charging. |
| **Automatic monitoring resume** | After a device reboot, monitoring resumes only when setup is complete and the monitoring master switch was enabled. |
| **Five-second preview** | Preview the saved sound, TTS message, volume, and vibration settings. |
| **Appearance themes** | Choose System, Light, Dark, or **Comfort** (護眼) theme. |
| **Setup wizard** | Review notification, battery-optimization, full-screen notification, and Quick Settings setup. |
| **APK sharing** | Share the installed app's APK using Android's share sheet. |

## Quick Settings tile

The Quick Settings tile is dedicated to the **charging-limit alarm**. Its active state follows the charging-limit alarm setting. It does not enable or disable the low-battery alarm; configure that alarm separately in the app.

To add the tile:

1. Expand Android's Quick Settings panel.
2. Open the tile editor (usually the pencil icon).
3. Find **VoltHalt**, add it to the active tiles, and save.

Long-pressing the tile opens VoltHalt. The exact editing steps and tile layout may vary by Android version and device manufacturer.

## Privacy and file access

- The app manifest does **not** request `android.permission.INTERNET`.
- The app does not include an in-app account, analytics, advertising, or telemetry feature.
- Alarm preferences are stored locally with Preferences DataStore.
- Custom audio is selected with Android's Storage Access Framework. VoltHalt does not request broad storage access; it retains read access only to the file the user selects, when the document provider grants a persistable permission.
- Android's selected TTS engine may have its own network behavior. Likewise, a document provider may expose cloud-backed files. Those behaviors are controlled by the selected system component or provider, not by an Internet permission in VoltHalt.
- The app requests `RECEIVE_BOOT_COMPLETED` so it can resume monitoring after boot, but only when setup has been completed and the saved monitoring master switch is enabled.

VoltHalt itself cannot make ordinary Internet connections without the Internet permission, but the behavior of separately selected Android system services and document providers is outside the app's control.

## App showcase

<table border="0">
  <tr>
    <td width="40%" align="center" valign="top">
      <img src="https://github.com/user-attachments/assets/f488f14a-6bcf-4a84-988b-7b0889a0d21f" width="90%" alt="VoltHalt screen 1" />
      <br/><br/>
      <img src="https://github.com/user-attachments/assets/09da40cc-bd1b-4e1f-8d8c-450a40e568c3" width="90%" alt="VoltHalt screen 2" />
      <br/><br/>
      <img src="https://github.com/user-attachments/assets/3364be52-2187-4d4d-81a2-cb0a22ff7800" width="90%" alt="VoltHalt screen 3" />
    </td>
    <td width="60%" align="center" valign="top">
      <img src="https://github.com/user-attachments/assets/ae11a7da-b821-4655-9a00-8705b3eb3da6" width="95%" alt="VoltHalt feature walkthrough" />
    </td>
  </tr>
</table>

> Screenshots may not yet show every feature in the current `test_fix` branch, including the Comfort theme and custom audio-file picker.

## Download APK

This repository currently publishes a **Debug APK as a GitHub Actions artifact**. It does not currently publish a GitHub Release.

1. Open [VoltHalt Actions](https://github.com/love80312/VoltHalt/actions/workflows/android-apk.yml).
2. Select the latest successful **VoltHalt Android APK** run for the `test_fix` branch.
3. Open the run's **Artifacts** section and download `app-debug.apk`.

The workflow retains artifacts for **14 days**. An expired artifact must be downloaded from a newer successful run. A Debug APK is intended for testing and is not a signed production release.

Android may ask you to allow installation from the browser or file manager used to open the APK. Only install APKs from sources you trust.

## Build from source

### Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK Platform 34 and Build Tools 34.0.0
- Gradle 8.4 (the repository includes the Gradle wrapper)

### Build with Android Studio

1. Clone this repository: `https://github.com/love80312/VoltHalt.git`.
2. Open the repository folder in Android Studio.
3. Allow Gradle sync to complete.
4. Select **Build → Make Project**, or run the `app` configuration on a connected device or emulator.

### Build from a Windows command prompt

From the repository root, run:

```bat
gradlew.bat --no-daemon :app:assembleDebug
```

The Debug APK is generated at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

To create a signed release APK, use **Build → Generate Signed Bundle / APK** in Android Studio and follow the signing wizard. Keep your signing key private and do not commit it to the repository.

## Tech stack

- **Language:** Kotlin 1.9.22
- **UI:** Jetpack Compose and Material 3
- **Settings storage:** Preferences DataStore
- **Monitoring:** Android foreground service and battery-state broadcasts
- **Audio:** Android `MediaPlayer` and `TextToSpeech`
- **System integration:** Quick Settings `TileService`, notifications, `FileProvider`, and Storage Access Framework
- **Minimum SDK:** Android 8.0 (API 26)
- **Compile / target SDK:** Android 14 (API 34)

## Project structure

```text
VoltHalt/
├── .github/
│   └── workflows/
│       └── android-apk.yml              # Debug APK CI workflow
├── app/
│   └── src/main/
│       ├── java/com/im_atp/volthalt/
│       │   ├── MainActivity.kt          # Main entry point and navigation
│       │   ├── AlarmActivity.kt         # Full-screen alarm screen
│       │   ├── BatteryService.kt        # Background battery monitoring
│       │   ├── AlarmPlayer.kt           # Ringtone and TTS playback
│       │   ├── AlarmTileService.kt      # Quick Settings tile
│       │   ├── PreferencesManager.kt    # Preferences DataStore access
│       │   ├── BootReceiver.kt          # Resume monitoring after device boot
│       │   └── ui/
│       │       ├── screens/
│       │       │   ├── MainScreen.kt
│       │       │   ├── SettingsScreen.kt
│       │       │   └── SetupScreen.kt
│       │       └── theme/
│       │           └── Theme.kt         # System, light, dark, and Comfort themes
│       ├── res/                         # Strings, icons, and XML resources
│       └── AndroidManifest.xml
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew.bat
└── README.md
```

## Permissions

VoltHalt declares the following Android permissions for its current features:

| Permission | Purpose |
|---|---|
| `FOREGROUND_SERVICE` | Allows the app to run its battery-monitoring foreground service. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Declares the special-use foreground-service type on supported Android versions. |
| `VIBRATE` | Enables vibration alerts. |
| `MODIFY_AUDIO_SETTINGS` | Allows the app to temporarily set the system alarm-stream volume for alarm playback and restore the previous value when playback stops. This is a normal permission and does not require a runtime permission prompt. |
| `POST_NOTIFICATIONS` | Allows monitoring and alarm notifications on Android 13 and later. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Allows the setup flow to request exemption from battery optimization. The user decides whether to grant it. |
| `WAKE_LOCK` | Keeps the CPU awake while the alarm screen or alarm handling requires it. |
| `USE_FULL_SCREEN_INTENT` | Allows eligible alarm notifications to request a full-screen presentation; Android may require the user to enable this access. |
| `RECEIVE_BOOT_COMPLETED` | Allows the app to receive the system boot-completed broadcast and resume monitoring when setup is complete and the monitoring master switch is enabled. |

The app does **not** declare `INTERNET`, `READ_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`, or `MANAGE_EXTERNAL_STORAGE`. It does not restart monitoring after boot when the master switch is off or setup has not been completed.

The alarm-volume setting temporarily changes the device's system alarm stream while an alarm is active. VoltHalt restores the previous value when playback stops normally. Android may terminate an app process without running cleanup code, so restoration cannot be guaranteed after an abrupt process termination or device shutdown.

## Issues and contributions

For bug reports, include your Android version, device model, steps to reproduce, and expected versus actual behavior. Please use the [Issues page](https://github.com/love80312/VoltHalt/issues).

Contributions and pull requests are welcome. Please keep changes focused and verify that the Android Debug build succeeds before submitting a pull request.

## Modification attribution

The fork-specific modifications introduced in this `test_fix` development work—including code changes, bug fixes, UI and theme adjustments, Traditional Chinese localization, audio-file selection, Quick Settings behavior, and documentation updates—were prepared and edited by **ChatGPT (OpenAI)** at the direction of the repository maintainer.

The repository maintainer reviewed the changes, performed device testing, and manages the repository.

This attribution applies only to modifications made for this fork as part of this work. It does not claim authorship of the original upstream VoltHalt project or third-party components. The original upstream project remains credited to [im-atp/VoltHalt](https://github.com/im-atp/VoltHalt), and its original MIT license and copyright notice are retained.

## License and upstream

VoltHalt is distributed under the [MIT License](LICENSE). This repository is a personal fork of [im-atp/VoltHalt](https://github.com/im-atp/VoltHalt); the upstream project's license and copyright notice are retained.

---

<div align="center">

**If VoltHalt is useful to you, consider giving the project a ⭐.**

</div>
