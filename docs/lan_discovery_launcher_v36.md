# Halo v36 — automatic LAN discovery and compact launcher

**Scope:** Android Java only. Native Halo gameplay, maps, protocol 52, bots,
campaign saves, signed LAN updater TCP 5162, and installer signature checks
remain unchanged. Development worktree lives on Forge's secondary volume.

## The observed problem

After downloading the host's APK and reinstalling it while the host remains
in the match, a friend may return to an empty Host IP field. Requiring the
host to exit gameplay to read the address breaks the join/update flow.

## What changed

A hosted Halo game advertises its presence only on the current private
Wi-Fi/hotspot network. Joining clients automatically send a short UDP probe
on port 5163, using limited/direct broadcast and (when available) the
Wi-Fi gateway as fallback. The running host answers from its private LAN
interface with a nonce-bound constant response; it sends no player data,
game maps or APK bytes in discovery. The client uses the responder's actual
private source address, never an arbitrary IP supplied in a packet. Both
directions require the same private LAN subnet.

**The discovery response is not authentication.** Signed APK updates still
use the existing fixed manifest, hash, package name, monotonic version code
and signing certificate verification. Normal Halo gameplay still does its
own protocol handshake. Multiple discovered hosts are presented for
selection rather than silently choosing one.

The launcher now has compact **Campaign, Bots, Host, Join, Tools** tabs. Play
is still the single fixed footer action. Maps, diagnostics, backups and custom
imports are on Tools; commander/kilstreak controls are behind Advanced Game
Options. A host can still show/copy/share their own IP, but the IP field is
hidden by default. A guest normally selects Join, allows discovery and taps
Play. If broadcast/unicast discovery fails, Manual IP remains available.

The host's discovery responder runs independently of opt-in APK sharing;
turning off APK sharing does not prevent the normal multiplayer discovery
probe. No cloud discovery service, accounts, background persistent services,
or broad network scan were added.

## Upgrade and compatibility

- The transition **v35 -> v36** may require one last manual host IP on old
  guests. Both phones must run v36 for this *new* discovery handshake.
- Host always installs the verified **v36 APK first**, then invites friends
  to update using their existing LAN update method or private APK transfer.
- Installer action must be **Update**, never Uninstall: existing data stays.
- If one phone can't discover another, confirm both are on same hotspot,
  update sharing is enabled if needed, and use Manual IP. If even manual IP
  fails, investigate hotspot client isolation, Wi-Fi restrictions and ports.
- Manual IP remains an explicit fallback; we must not claim all carrier
  hotspot implementations will pass broadcast packets without field testing.

## Verification

1. `python3 tools/test_android_launcher_layout.py`
2. `python3 tools/test_lan_discovery_launcher.py`
3. Pure JDK test: compile `LanUpdateProtocol.java`,
   `LanDiscoveryProtocol.java` and `LanDiscoveryProtocolTest.java`;
   run `com.halo.decomp.LanDiscoveryProtocolTest`.
4. Build the signed experimental APK with monotonically increasing
   versionCode **36** and the same package/signing certificate.
5. Physically verify host v36 stays in a LAN match while guest v36 updates,
   relaunches, discovers the host without typing an IP, and rejoins.
6. Verify two hosts prompt for selection, no-host gives Manual IP,
   a guest never starts the host beacon, and map/diagnostic Tools still open.
7. Keep v35 on the phone until the signed v36 artifact is independently
   checked. If a field test fails, manually use v35 rather than uninstall.

All source checks are safe to run on Forge. Use one protected Android-only
GitHub Actions exact-head build if necessary; never trigger the multi-platform
push build while iterating.
