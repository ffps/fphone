# <p align="center"><img src="icons/fphone.svg" /><br />fPhone — персональная система видеосвязи</p>


## Лицензия
Лицензия: свободное использование.


## User profile and avatars

- `PUT /api/v1/me` with `{"name":"Display name"}` changes the authenticated user's display name (1-64 Unicode characters).
- `PUT /api/v1/me/avatar` uploads raw JPEG bytes (`Content-Type: image/jpeg`), up to 2 MiB and 2048x2048 pixels.
- `DELETE /api/v1/me/avatar` removes the user's own photo and falls back to the administrator's photo.
- Administrator photo: `avatars/<login>.jpg`; user photo: `avatars/<login>.user.jpg` (takes priority).
- `GET /api/v1/contacts` includes `last_seen` (Unix seconds) and the avatar version.
- Logins are ASCII identifiers: `[A-Za-z_][A-Za-z0-9_]{0,63}`.


The WebSocket `call.signal` message also forwards `kind: "camera"` with an `enabled` boolean so the peer can replace a disabled remote video with the caller's avatar.
