# Android LAN multiplayer: simple setup for 2 or more phones

This v2 launcher is built on the physically tested v1 unicast-discovery Android APK.

## What has been tested

On November 6, 2026, two physical Android phones completed a Halo CE co-op
campaign level over one phone's hotspot. The person hosting the HALO GAME
was not the hotspot owner. The host's debug.txt confirms both players joined,
stayed connected during gameplay and reached the next level. A separate
client-side co-op bug remains: after the joining player died, the host's
Spartan or visual effects sometimes disappeared on the joining phone until
the host died and respawned. That is NOT addressed by this UI-only v2 change.

This setup has not yet been physically tested with three or more phones.
No changes are made to the native LAN discovery workaround, netcode,
co-op object synchronization, max players or the Android package identity.

## Easy setup for a group

1. Everyone installs the same Halo APK build and supplies their own lawful
   Halo Xbox disc image for the required game maps.
2. Everyone connects to ONE phone hotspot or ONE Wi-Fi LAN.
   The hotspot owner is not required to host the Halo game.
3. The HALO GAME HOST selects "Host game" in the launcher. The launcher
   displays potential Wi-Fi/hotspot IPs. If a single interface is found,
   the share field is prefilled. Confirm it and use "Copy host IP" or
   "Share invite" to give the SAME IP to everyone joining.
4. The host taps "Save settings & Open Halo", creates a campaign co-op or
   multiplayer System Link lobby inside Halo, and waits for players.
5. Each JOINER selects "Join game", pastes the host's IP, and opens Halo.
   In Halo, join the host's game through System Link.
6. A third, fourth, etc. joiner uses that same IP. Joiners never need to
   know each other's IPs. No guest list is stored on the host.

Only one phone hosts the Halo game. The native v1 code sends a direct
unicast System Link discovery reply to every searching phone in LAN-only
mode, independent of the number of joiners. The IP input is the game's
network.broadcast search destination on joiners; the host clears any
saved peer setting. The network.address socket bind is optional, blank
by default and accessible under Advanced / troubleshooting.

## Advanced and failure cases

- LAN-only is enabled by default, and internet play can be enabled from
  Advanced. Internet invitations staged through halo://join are preserved
  and require internet mode. Internet launches clear a stale LAN target.
- IPs can change after reconnecting. Reopen the launcher after connecting
  to the hotspot. If multiple IPs are shown, confirm the correct Wi-Fi
  interface IP and edit the host's share field; if none, check Android
  Wi-Fi/hotspot settings. The share field does NOT alter socket binding.
- An older manual local-IP override is preserved until explicitly cleared
  under Advanced. The launcher displays a warning that this can become
  stale after switching hotspots.
- A running or indeterminate Halo :game process blocks saving changed
  network settings; Force stop Halo from Android App Settings first.
- LAN-only refers to discovery settings, not a guarantee that mobile data
  is unused. If feasible, temporarily disable cellular data for the
  offline-only acceptance test without disabling the hotspot.
- Hotspot client limits and isolation policies vary, as do phone hardware
  and memory. A 3+ player co-op LAN session still requires a physical test.
- The debug log is Android/data/com.halo.decomp/files/debug.txt. Capture the
  host and all client logs after problems, before uninstall or map reimport.
- The public builder produces a debug APK with an ephemeral signing key,
  and Android cannot install an APK with a different signature over the
  existing installation. Uninstalling an older differently-signed APK
  deletes its app-specific imported maps. A durable signing/upgrade
  strategy would need separate secure implementation.
- No Halo game data or copyrighted map files are included or uploaded
  to GitHub, the builder or the APK.

## Source-level checks

- Compile LanConfig.java and LanConfigSmokeTest.java using javac -proc:none.
- Run the LAN configuration smoke test; includes host mode, multiple
  joiners entering one host IP, input validation and preserving unrelated
  TOML settings.
- Build Android only at an exact source SHA in the isolated public
  GitHub Actions builder (not from the privileged Forge account).
- Check package identity, native arm64 library, source SHA, APK SHA-256,
  signing and artifact receipt.
- Test three physical devices on the hotspot: discovery, all lobby joins,
  level progression, respawns, and sustained play.
- Preserve the known working v1 branch and APK for rollback.
