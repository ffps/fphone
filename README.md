# fPhone - Family video calls (Android client)

Family video calls. This repository currently holds the **push test app**: it shows the push
token and logs the messages that the fPhone server sends, so that waking the phone for an
incoming call can be verified before the real client is written.

## Build and release (GitHub Actions)

A release is made by either:

- pushing a tag `vX.Y.Z`, or
- pushing a commit to `main` whose message contains `[release vX.Y.Z]`, for example
  `Add push test [release v0.1.0]`.

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

## Local build

Install JDK 17, the Android SDK and Gradle 8.7, put your `google-services.json` into `app/`
and run:

```
gradle assembleDebug -PfphoneAppId=<package_name from google-services.json>
```

## Testing the push with the server

1. Install the APK, open the app, allow notifications. The screen shows the push token.
2. Press **Copy token** and send it to yourself, or type it on the server machine.
3. On the server machine run `fphone -c fphone.yaml push-test TOKEN`.
4. The phone should show a notification and a line `push: priority=1 ...` in the log, also
   when the app was closed. Repeat after the screen was off for some minutes to see how the
   phone treats pushes in battery saving mode (`priority=2` means the push was delayed).

## If no push arrives

The server answering `HTTP 200` only means that Google accepted the message. Then check:

1. The log shows a line `push service started` and `new token` after install. If they are
   missing, nothing from Google reaches the phone at all.
2. Press **Check FCM ports**. The phone keeps one permanent connection to
   `mtalk.google.com` (ports 5228-5230, fallback 443). If all fail on Wi-Fi, try mobile data
   (and the other way round): a router, DNS filter or VPN may block them.
3. Look at the battery settings of the phone (vendor battery managers can block pushes).

## Icon

`art/fphone.svg` is the source. The launcher icons were rendered from it: PNG files for old
Android versions and a vector adaptive icon for Android 8 and newer.
