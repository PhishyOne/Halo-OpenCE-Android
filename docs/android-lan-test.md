# Android System Link test build (LAN-focused branch)

This branch targets a testable Android hotspot discovery workaround. It is a **candidate**, not a confirmed fix.

## Differences from upstream

1. The Android launcher shows a phone-friendly System Link setup before starting Halo when game maps are installed. It can save the current phone's LAN IPv4 address (optional), the peer phone's IPv4 address (optional), and a LAN-only option to the existing `config.toml` without losing unrelated settings.
2. In LAN-only mode (`network.online = false`), a host also sends a unicast reply to the IP/port that sent a discovery search. Normal UDP 5151 search replies are rate-limited to the existing advertisement interval; off-port search replies and internet play retain their prior behavior.
3. A plain JDK smoke test covers preserving other config sections, IP validation, persistence, and malformed duplicate network sections.

No new permissions, internet services, game assets, or extra native libraries are added. The package remains `com.halo.decomp`.

## Two-phone test

Use the **same candidate APK** on both phones. The APK does not include Halo game data; each phone must import its own lawful Xbox Halo disc image after uninstalling the differently signed stock APK.

1. Force-stop Halo on both phones **before changing LAN settings**, then connect both phones via one phone's hotspot; verify current IPs under Android Wi-Fi/hotspot settings. The observed 2026-11-04 IPs were hotspot `10.18.179.225` and client `10.18.179.126`, but leases can change.
2. Open the new **System Link** launcher on both phones. Check **LAN only** on both.
3. Leave **This phone's LAN IP** empty for auto detection first.
4. On the joining phone, enter the host phone's current IP in **Other phone's LAN IP**; the host may leave peer empty.
5. Save and start on both phones. Host a System Link game on one phone, then search on the other for at least 30 seconds.
6. If not discovered, force-stop both, enter *each phone's own IP* into **This phone's LAN IP** and the *other phone's IP* into **Other phone's LAN IP** on both sides; relaunch and repeat.
7. Repeat with the roles reversed, preserving the same hotspot ownership. Keep the games foregrounded.

If discovery still fails, upload both devices' `debug.txt` files and the effective `[network]` sections, plus the current IP addresses. An earlier direct UDP test to port 5150 succeeded, but did not verify broadcast or the reverse discovery reply on UDP 5151. A device-to-device match lasting at least several minutes is required before claiming success.

**Lifecycle and invites:** The game reads its network settings on startup. If the launcher detects a running `:game` process before Save, it refuses to overwrite settings or start a potentially stale session, and offers Android App Settings. Use **Force stop** there, then reopen Halo. An incoming `halo://join/...` invite is first staged in `pending_invite.txt`, which the native game does not consume. It stays pending through first-time disc import and even Android **Force stop/reopen**; the launcher unchecks LAN-only and requires **Save & Play** before atomically moving it to native `join_link.txt`. The setup screen also has a **Discard internet invitation (use LAN)** action. If a previous `:game` process exists or Android cannot verify its absence, Save & Play blocks and opens Android App Settings for a manual Force stop. Physical phone testing is required to confirm that process enumeration works on both devices. A source review and physical-device checks must verify this behavior; no Android runtime success is claimed.

## Build

The source is public AGPL-3.0-or-later. Never upload Xbox game maps to GitHub or ship them in the APK. A public, isolated Android-only builder will be used for the first candidate artifact; existing upstream multi-platform CI in the fork remains disabled. No stable signing key is stored in candidate code or a candidate-controlled workflow, so the debug APK initially requires uninstalling other signatures. This deletes game data.

Build locally only in a safely isolated execution environment, not the privileged Forge account. Android host NDK r27c and Clang support for the ILP32 ARM guest must be verified. The game needs API 28+ and OpenGL ES 3.0; mobile GPU and 4 GB memory performance remain device-dependent.
