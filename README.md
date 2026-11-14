# fPhone (Android client)

Family video calls over your own server (see the fPhone server). Native WebRTC, all call media
goes through the TURN relay of the server, so it works between phones behind mobile NAT.

* Android 8.0 (API 26) or newer.
* Login with the user name and password that the administrator created on the server.
* Incoming calls wake the phone through a push message (Firebase Cloud Messaging), also when
  the app is closed; the call screen is shown over the lock screen.
* The server can use its own certificate. In that case enter the certificate fingerprint
  (`sha256/...`, printed by `fphone fingerprint` on the server) in the login form: the app then
  trusts exactly that server key and nothing else.

## First start

1. Open the app, enter the server address (for example `https://domain.com`), optionally the
   certificate fingerprint, the login and the password.
2. Allow the microphone, the camera and notifications.
3. On Android 14 and newer allow "full screen notifications" when the app asks (otherwise an
   incoming call is not shown on the lock screen), and switch off the battery restriction for
   the app (otherwise the phone may delay pushes).

## Build and release (GitHub Actions)

A release is made by either:

- pushing a tag `vX.Y.Z`, or
- pushing a commit to `main` whose message contains `[release vX.Y.Z]`, for example
  `Native WebRTC client [release v0.2.0]`.

The workflow builds a signed APK named `fPhone-X.Y.Z.apk` and attaches it to a GitHub release
`vX.Y.Z`. Other pushes to `main` only check that the app builds and keep a debug APK as a
workflow artifact for 7 days.

`versionCode` is calculated as `X*10000 + Y*100 + Z`, so every new version number is higher.

### Repository secrets

| Secret | Content |
|---|---|
| `KEYSTORE_BASE64` | the signing keystore file, base64 encoded |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |
| `GOOGLE_SERVICES_JSON_BASE64` | `google-services.json` from the Firebase console, base64 encoded |

The application ID is taken from `google-services.json` during the build, so no personal
names are stored in this repository, and `google-services.json` itself is never committed.

How to make the base64 text on Windows (PowerShell):

```
[Convert]::ToBase64String([IO.File]::ReadAllBytes("google-services.json")) | Set-Clipboard
```

On Linux: `base64 -w0 google-services.json`.

### WebRTC library version

`app/build.gradle.kts` asks for `io.github.webrtc-sdk:android:125.+` (the newest build of the 125
series). After the first successful build, copy the exact version from the Gradle log into
that line, so that builds are reproducible.

## Local build

Install JDK 17, the Android SDK and Gradle 8.7, put your `google-services.json` into `app/`
and run:

```
gradle assembleDebug -PfphoneAppId=<package_name from google-services.json>
```

## How it works

* `CallManager` is the brain: it keeps the WebSocket connection to the server only while the app
  is visible or a call is going on, and drives the call state (outgoing, incoming, connecting,
  active). Screens only show its state.
* `RtcEngine` wraps one WebRTC peer connection (microphone, optional camera, TURN relay,
  ICE restart after a lost connection).
* `FcmService` receives the push message for an incoming call; `Notifications` shows the ringing
  notification with a full screen intent; `CallService` keeps the call alive in the background.
* The "Log" button on the main screen shows the diagnostic log and the push token (also useful
  for `fphone push-test TOKEN` on the server).

## Icon

`art/fphone.svg` is the source. The launcher icons were rendered from it: PNG files for old
Android versions and a vector adaptive icon for Android 8 and newer.
