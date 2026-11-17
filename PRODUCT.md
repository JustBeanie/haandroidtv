# HA Quick Access

HA Quick Access is an independent Android TV client for a user-managed Home Assistant server. It gives household members quick remote-control access to lights, switches, climate, covers, scenes, fans, and alarms from a television.

The primary devices are NVIDIA Shield and modest Android TV hardware, including 1 GB devices. Navigation must work with a directional pad, Select, and Back, with visible focus, readable states, and TV-safe margins.

The approved redesign uses Favorites, All controls, and Settings. Favorites is the default destination. Select performs the quick action; holding Select opens details where supported. All controls supports discovery, filtering, search, and pinning. Setup separates the server address from the masked access token.

The app connects only to the user-entered HTTPS/WSS server and stores credentials encrypted with Android Keystore. Cached favorite labels and states support offline display; they never authorize offline commands. Changing the connection invalidates the cache. Forget connection removes local configuration and cache. No developer analytics or advertising services are included.

Reliability means bounded requests, generation-scoped sessions, atomic settings changes, predictable cancellation, and truthful connection status. Emulator checks support development; physical-TV performance, accessibility, Play pre-launch review, and reviewer access remain release gates. See docs/tv-redesign-validation.md for evidence and limitations.
