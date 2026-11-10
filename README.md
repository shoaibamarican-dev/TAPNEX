# TAPNEX — device-only Android source

This variant keeps the Android app project and removes Firebase Authentication, Firestore, Cloud Functions, server accounts, credits, and wallet dependencies. App selection and click-point settings are stored locally on the device.

## Included app features

- Existing TAPNEX dark interface, splash screen, app picker, and local device profile
- Locally stored selected target app
- Floating control panel that appears when the selected target app enters the foreground (requires the user to enable Android overlay and Accessibility permissions)
- User-configured click points, draggable/lockable positions, adjustable interval controls, and a press-and-hold control that stops when released
- AdMob banner integration; debug APKs use Google's official test banner ad unit
- GitHub Actions workflow that builds and uploads a debug APK automatically

## Removed

- Firebase login / Google sign-in
- Firebase Firestore and Cloud Functions
- Wallet, credits, and server-side session unlock

There is no cloud account or sync. Clearing app data or uninstalling the app removes its locally stored settings. Tapping an app in the App Library shows a "Watch ad to unlock" dialog (rewarded ad, with a Cancel option) before the app opens. Debug builds use Google test ads; release builds use the real TAPNEX AdMob units (set `TAPNEX_LIVE_ADS=true` to use real ads in debug too). The floating controls are drawn by the accessibility service (accessibility overlay), so they appear over games without the extra pop-up permissions. This source does not include game-specific rapid-fire presets or anti-cheat bypasses.

## Build the APK using GitHub (no local Android Studio required)

1. Download and extract this ZIP.
2. Upload **all files and folders inside** the extracted `TAPNEX` folder to your GitHub repository (the files `settings.gradle.kts`, `gradlew`, `gradle/`, `app/`, and `.github/` must be at the repository root).
3. Commit the files to the `main` branch (or `master`). The workflow also supports manual runs.
4. In GitHub, open the repository's **Actions** tab.
5. Select **TAPNEX Android APK**, then click **Run workflow** if it has not already started after your push.
6. Open the completed workflow run. Under **Artifacts**, download **TAPNEX-debug-APK**.
7. Extract the artifact ZIP; it contains `app-debug.apk`. Transfer that APK to your Android device and install it.

The first GitHub build may take several minutes while Gradle downloads dependencies. The workflow uses GitHub-hosted build resources and does not require Firebase or Blaze billing.

## Build details and limitations

- Application ID: `com.tapnex.autoclicker`
- Minimum Android version: Android 8.0 (API 26)
- Compile/target SDK: Android 15 (API 35)
- The GitHub workflow creates a **debug-signed test APK**, not a Play Store release-signed APK.
- Debug ads use Google's official test banner unit. Do not click test ads repeatedly or use test ads in a published release.
- Android Accessibility controls are general-purpose and user-configured. Use them responsibly and follow the rules of apps in which they are enabled.

This archive is source code for GitHub Actions to build. It is not itself an APK, and a successful build still needs to be confirmed by the GitHub Actions run.
