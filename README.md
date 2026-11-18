# Кукуха (Android client)

Family video calls over your own server (see the fPhone server). Native WebRTC, all call media
goes through the TURN relay of the server, so it works between phones behind mobile NAT.

* Android 8.0 (API 26) or newer.
* Login with the user name and password that the administrator created on the server.
* Incoming calls wake the phone through a push message (Firebase Cloud Messaging), also when
  the app is closed; the call screen is shown over the lock screen.
* The server can use its own certificate. In that case enter the certificate fingerprint
  (`sha256/...`, printed by `fphone fingerprint` on the server) in the login form: the app then
  trusts exactly that server key and nothing else.

## Screens

Material 3 (Jetpack Compose); light or dark theme and text size follow the system settings.
The colors are green like the app icon; "Colors from the wallpaper" in the settings switches to
Material You colors on Android 12 and newer.

* **Contacts**: people with their photo and an "online" dot, a single call button that starts a video call (the camera can be switched off during the call).
  Press and hold a contact and drag it to change the order (the order is kept on the phone). A
  contact whose app is closed can still be called: the phone is woken up by a push message.
* **Recents**: the call history of this phone (missed calls in red, a badge on the tab and a
  notification for a missed call). The button on the right calls back.
* **Settings** (menu with three dots, top right): account, server, colors, the state of the
  permissions that calls need (notifications, full screen notifications, battery, microphone,
  camera), and call reliability permissions. **About** contains the version, updates, diagnostic log and project link.
* **Call screen**: incoming call (Decline / Answer), calling, and the running call with compact icon-only controls in one bottom row, a movable local preview and the remote user's avatar when their camera is off.

### Photos of users

The server uses `avatars/<login>.jpg` as the administrator-set photo and `avatars/<login>.user.jpg` as the photo uploaded by the user. The user photo takes priority; removing it restores the administrator-set photo. Users can change their display name and upload or reset their own photo in Settings. The app downloads a photo once and keeps it; a changed file is picked up automatically. User uploads are JPEG images up to 2 MiB and 2048×2048 pixels. Users without a photo get a colored circle with the first letter.

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
  `Native WebRTC client [release v0.3.0]`.

The workflow builds a signed APK named `Kukukha-X.Y.Z.apk` and attaches it to a GitHub release
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

## Server CLI additions in 0.3.0

- `fphone push-test id=ID` sends a test push to one registered device; `login=LOGIN` targets all
  devices of a user. Optional `title=...` and `text=...` override the defaults.
- `fphone user name LOGIN` prints the current display name; `fphone user name LOGIN NAME` changes it.
- `fphone device log ID` requests the diagnostic journal over the phone's existing WebSocket and
  saves it as `data_dir/ID.log`. The phone must be connected with the app in the foreground.

The server's CLI control listener is bound only to `127.0.0.1` (default port 8098); it is not
exposed to the network. Change `control_port` in the server configuration if that local port is
already in use.

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

The Android launcher icon uses the supplied Кукуха icon set: adaptive foreground/background
resources plus density-specific PNGs for older Android versions.

## Updates

The main screen shows the installed version and the button **Check for updates** (it also
checks once when the app starts). When a newer release exists, the button **Update** appears: it
downloads `Kukukha-X.Y.Z.apk` from the GitHub release and starts the system installer.

* The repository is taken from the build (`github.repository` of the workflow), nothing is
  stored in the source. The repository must be public.
* The first time, Android asks to allow installing apps from fPhone (a settings screen opens).
* An update is installed only if it is signed with the same key as the installed app, so keep
  using the same keystore for all releases.
* Updates are blocked while a call is going on.
