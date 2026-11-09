# TAPNEX

TAPNEX is a general-purpose Android accessibility clicker with Firebase authentication, server-verified rewarded-ad credits, a selectable target app, and animated floating controls.

## Production configuration

The production AdMob configuration is already wired into the release build:

- AdMob App ID: `ca-app-pub-2888936097455517~2870896170`
- Banner ad unit: `ca-app-pub-2888936097455517/1557814503`
- Rewarded ad unit: `ca-app-pub-2888936097455517/9384333631`

Debug builds always use Google's test ad units. Test ads never grant credits.

Cloud Functions use `functions/.env`. Copy `functions/.env.example` to `functions/.env`; the template contains the production rewarded ad unit ID. Do not commit `functions/.env`.

## Firebase

The existing Firebase project integration and package name are preserved. Firebase Authentication and Firestore must be enabled in the Firebase project. Google sign-in additionally requires a valid web OAuth client and the installed signing certificate fingerprints in Firebase.

Deploy backend functions and Firestore rules with:

```text
cd functions
npm ci
npm test
cd ..
firebase deploy --only functions,firestore:rules --project tapnex-74d6d
```

## Credits and rewarded ads

Credits are read from the authenticated Firebase account. The Android client never writes a credit balance.

For a production rewarded ad:

1. TAPNEX loads the configured production rewarded unit.
2. AdMob sends the completed-ad SSV callback to `admobRewardCallback`.
3. The Cloud Function verifies Google's signature and key ID.
4. The function accepts only the configured production ad unit.
5. The function creates one claim document per transaction ID and grants exactly 5 credits.
6. Replayed transaction IDs are rejected as already credited.
7. The app refreshes Firestore and reports success only after the server-side balance has changed.

The server ignores the reward amount supplied by the client/callback and uses the fixed 5-credit reward.

## Floating controls

After Display over other apps and Accessibility permissions are enabled, the user can select a target application from the installed-app picker. When that target enters the foreground, TAPNEX automatically shows the floating control. When the target leaves the foreground, TAPNEX hides the controls and stops active click sessions.

The floating menu includes:

- Add Clicker
- Start/Stop Clicking
- Lock Clicker
- Click Speed
- Minimize/Restore
- Close floating menu

Clickers are red circular overlays. Their position, enabled state, lock state, opacity (10–50%), size, and interval are saved locally. Accessibility gestures are sent only to explicitly configured points and only during an active user-started session.

## Settings

The Settings screen intentionally contains permissions only:

- Display over other apps
- Accessibility
- Notifications on Android versions that require notification permission

Clicker configuration is available from the Home screen and floating menu, not from Settings.

## Build

The project uses the included Gradle wrapper and JDK 17. Android SDK 35 is required.

```text
./gradlew assembleDebug
./gradlew assembleRelease
```

Release signing is not included in the repository. Configure a private release keystore through Gradle properties or environment variables when a signed release is required.

## Secrets excluded from this repository

Do not commit:

- `.env` files containing private values
- Android keystores
- Private signing keys
- Firebase service-account private keys
- Passwords or personal credentials

The existing GitHub Actions workflow and Gradle wrapper are preserved.

## Verification

The supplied environment did not provide a usable Android Gradle distribution download or a complete Android SDK, so an Android APK build could not be truthfully verified in this repair environment. Cloud Function tests were also blocked because the environment could not complete the npm dependency installation. The project package was nevertheless inspected, modified, and packaged without including generated dependency folders or private credentials.
