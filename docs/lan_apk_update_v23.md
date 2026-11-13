# v23: Signed Halo APK updates over a local hotspot

**Status:** Source candidate on `halo/lan-update-v23`, based on verified
v22 head `4cfb51c`. No v22 game code or LAN wire packets need modification.
An actual upgradeable v23 APK must pass the Android-only workflow before
delivery. The initial v23 update needs manual installation on all phones.

## User-facing workflow

1. Everyone has v23 or later installed **once** by the existing signed-APK
   route. v22 and older lack the local update listener/client.
2. The game host chooses **LAN / SYSTEM LINK → HOST** in the Halo launcher,
   leaves **SHARE MY HALO VERSION WITH LAN PLAYERS** checked (ON by default),
   and starts Halo. Sharing is OFF in ordinary, offline, guest and internet
   sessions. The game stays open while a separate daemon accepts requests.
3. Friends connect to the same phone hotspot or Wi-Fi LAN. In their Halo
   launchers, select **LAN / SYSTEM LINK → JOIN**, enter the host's LAN IP,
   and tap **Play**. The launcher checks the host on TCP 5162 *before* the
   incompatible network protocol can reject the join. There is also an
   explicit **CHECK HOST UPDATE** button in the join panel.
4. If the host has a newer version of the **same package**, the joiner is
   asked to download it. The APK is streamed directly from the host's
   *installed* APK (`ApplicationInfo.sourceDir`) without a second 40 MB
   copy on the host. A progress bar shows the transfer.
5. The joiner checks exact Content-Length, SHA-256, package name, strictly
   increasing version and identical APK signing certificates (the trusted
   locally installed Halo signer). Only then is the original read-only
   `UpdateProvider` given the candidate APK. Android's system installer
   still asks the joiner to approve the update and may ask the first-time
   "Allow from this source" permission. It is **not** a silent install.
   A successful upgrade retains Android app data, maps and settings.
6. If no update listener answers, the automatic pre-join check falls back
   to existing Halo startup; the game's own LAN protocol still rejects
   incompatible versions. A guest with a newer APK than the host is told
   to ask the host to update. A different-package server is rejected.

## Threat and network boundaries

- TCP **5162** on allowed **wlan/ap/swlan/softap/eth/br/rndis/usb**
  interfaces only. Each listener is bound to a private IPv4 address on the
  actual interface, not the wildcard address or cellular `rmnet`/VPN.
  Peers must have private addresses in that interface's actual subnet,
  prefix /16–/30. Where Android hides hotspot interfaces or supplies a
  broader/unusual subnet, the server fails closed and normal LAN remains
  unaffected. No public-internet fallback or cloud dependency.
- Only GET `/halo/manifest` and GET `/halo/apk`. No directory traversal,
  arbitrary filenames, uploads, proxies, redirects or remote execution.
  Responses carry bounded Content-Length and close after each request.
  Header count and line lengths are limited. Up to two workers with two
  waiting connections, and file hashing done off the game thread.
- The joiner permits only canonical dotted-quad RFC1918 host IPs. It uses
  direct Java sockets, not a global cleartext-traffic exception or HTTP
  redirect. Incomplete/oversize downloads fail and delete the temp file.
  The downloaded *signing certificate*, not the host-advertised hash by
  itself, must match the installed Halo certificate. The Android package
  installer also checks APK signatures and upgrade compatibility.
- Server lifecycle is the host's HaloActivity `:game` process: start only
  from the explicitly selected local Host role and opt-in checkbox, stop
  on Activity destruction. Public release updater `Updater.start()` remains
  separate and test-build `HALO_BUILD_NUMBER=0` bypasses its GitHub check.
- Existing game ports (5150/5151), Commander/killstreak protocol 47 and
  multiplayer traffic are untouched.

## Source verification

```sh
python3 -m unittest -v tools.test_lan_apk_update
mkdir -p /tmp/halo-lan-test
javac -d /tmp/halo-lan-test \
  port/android/app/src/main/java/com/halo/decomp/LanUpdateProtocol.java \
  port/android/app/src/main/java/com/halo/decomp/LanUpdateSocket.java \
  tools/lan/LanUpdateProtocolTest.java
java -cp /tmp/halo-lan-test com.halo.decomp.LanUpdateProtocolTest
```

Both are part of the Android-only CI test gate, ahead of native/Gradle
compilation. They cover private-interface and subnet policy, exact fixed
GET routes, valid/bad manifests, binary responses, invalid HTTP and source
wiring. Signing approval is only source-checked until the app is run on
Android: source gates alone are not physical or SDK acceptance.

## Physical two-phone acceptance checklist

Use two **Android** phones, not an iPhone; iOS cannot install an APK.

- Same version: manual Check shows no update; Play joins normally.
- Host v24 / joiner v23: one update prompt, bounded download progress,
  Android installer opens and upgrades without losing imported maps.
- Disable sharing or select internet mode on host: no update listener
  (Play's standard LAN fallback remains).
- Wrong/invalid host address and no reachable interface: no crash or hang.
- Signing/package mismatch, truncated/tampered APK, changing host version:
  no installer is offered, existing installed game remains untouched.
- Concurrent transfers from two joining phones while the host is in its
  multiplayer pregame lobby. Observe whether the hotspot and bot count
  remain responsive. Check the separate LAN debug logs.
- Stop the host game: TCP listener closes. Android app data, game maps and
  the main site's online updater remain unchanged.

## Known limitations

The joiner must know the host's LAN IP as it does for normal System Link;
merely joining Wi-Fi does not launch the Halo app or force an update prompt.
Hotspot IP visibility varies by OEM, so a phone can be unable to bind the
local listener. Full Wi-Fi broadcast discovery, update/resume support,
automatically obtaining a private hotspot gateway, patch/delta downloads
and hands-off installation are **not** part of v23.
