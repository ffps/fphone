# Кукуха 0.3.0

## Android

- Updated launcher icon and application name to «Кукуха».
- Normalized server addresses: `domain.com` is saved as `https://domain.com/`.
- Fixed login-field text/background colors.
- Added a compact contact-details dialog with avatar, login, last-seen time, last-call type and time, and a call action.
- Added a brief refresh indicator to the contacts list.
- Added a square photo editor flow with rotation and 400×400 JPEG upload; avatar deletion falls back to the server default image.
- Replaced the display-name save button with an icon and added server-side uniqueness checks against other users' names and logins.
- Added an incoming-call camera-off choice.
- Fixed camera/microphone state changes while ringing, including camera selection before answer and camera activation after an audio-only answer.
- Added local ringback tone while an outgoing call is ringing.
- Delayed the in-call green banner by one second to avoid a flash during the transition to the call screen.
- Changed camera/microphone off controls to dark red and updated the camera-flip and missed-call icons.
- Combined missed-call notifications while retaining per-caller counts.
- Added timestamped, categorized diagnostic events and a menu indicator for unresolved push-registration issues.
- Added startup and monthly FCM-token refresh, pending-token retry, and server-acknowledged registration state.
- Updated the About screen icon, alignment, and GitHub source label.

## Go server and CLI

- Persisted call history in SQLite and exposed each contact's last-call summary through the contacts API.
- Added DEBUG push-delivery logs with recipient, device ID, FCM message ID/result, error, message type, and priority; FCM tokens are not logged.
- Extended `push-test` to target a device (`id=ID`) or every device of a user (`login=LOGIN`), with optional `title=...` and `text=...` arguments. The positional-token form remains supported.
- Added `user name LOGIN [NAME]` to show or change a display name.
- Added `device log ID`, which requests the phone's journal over its existing WebSocket and saves it as `data_dir/ID.log`.
- Added a loopback-only CLI control endpoint for the device-log request.
- Set defaults to a 90-day session lifetime, 60-second unanswered timeout, five concurrent calls, TURN enabled, and relay ICE policy.
