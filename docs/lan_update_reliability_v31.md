# Halo v31: LAN APK updater reliability and failure diagnostics

**Branch:** `halo/lan-update-fix-v31` from signed v30 `11bd2bc`.
**Scope:** app launcher and Android LAN updater only. No game protocols,
native bot/vehicle logic, maps, campaign files or import paths change.

## Field report and confirmed observations

Multiple friends on a phone hotspot reported an error when tapping
**CHECK HOST UPDATE**. There was no captured guest error text or two-phone
packet trace, so the exact field failure is **not confirmed**.

On the host Android phone, the package manager reported installed Halo
`com.halo.decomp.experiment` **versionCode 28**, while the signed
`Halo-v30.apk` existed in Downloads. Sharing the installed APK cannot
offer v29/30 to a guest until the host installs a newer version. A
newer guest must not be downgraded to the older host.

The source exposes three plausible reliability defects in v23–v30:

1. `HaloActivity` uses `android:launchMode="singleInstance"`, but
   only launched its TCP update listener from `onCreate`. A reused
   activity receiving `onNewIntent` did not reconfigure the listener.
2. Its listener scanned private hotspot IPv4 interfaces only once,
   rejecting Android interfaces that report a /32, broad or missing
   prefix. Some tethering interfaces report incomplete netmasks.
3. On a burst of simultaneous update requests, the host's two-worker,
   two-queued-client pool silently closed overflow connections. Guest
   errors gave only a generic unavailable message.

## v31 changes

- The host share role is synchronized in `onCreate` **and**
  `onNewIntent`. A new session stops any previous listener and starts
  a fresh host listener only when explicitly launched as
  **LAN → Host** with **Share My Halo Version** checked. Guest,
  internet and ordinary launches close an old listener.
- Host startup retries private Wi-Fi/hotspot discovery up to six times
  with bounded intervals. Interface and IP constraints still apply:
  never bind a wildcard address, public Internet, cellular `rmnet`
  interface or VPN. A tethering Wi-Fi/AP interface with an unusable
  prefix uses a conservative private **/24** peer restriction.
  A valid /16–/30 interface retains its real prefix, while ordinary
  ethernet with an invalid prefix still fails closed.
- The host has two active transfer workers, six waiting slots, and
  a backlog of eight. Overflow gets a bounded **503 Service
  Unavailable** response, not an unexplained reset. Guests on
  explicit **Check Host Update** retry transient busy/connect/timeout
  errors up to three bounded attempts. Automatic pre-join still makes
  only one probe, preserving old-host startup performance.
- The manual guest error explains closed port vs timed-out host vs
  unreachable address vs hotspot-subnet refusal vs busy update service,
  without displaying untrusted raw peer strings.
- The host writes a small `lan-update-status.txt` diagnostic under
  its **app-owned** external files directory with
  STARTING/READY/UNAVAILABLE/STOPPED. The user's
  **SAVE DIAGNOSTIC REPORT** feature now includes the current status
  with private IPv4 and email addresses redacted. The file does not
  include passwords, configuration, APK contents or user files.
- Launcher text explicitly notes that the host's update service starts
  after pressing **Play as Host**, not from merely enabling a hotspot
  while the app is still in its launcher.

## Security / preservation

The guest still validates the streamed SHA-256, exact size, installed
package ID, strictly newer version and identical APK signing certificate.
Android still asks the friend to approve installation. No downloads
occur merely from joining a hotspot; nothing installs silently.
The existing fixed GET `/halo/manifest`, `/halo/apk` paths and
TCP port **5162** remain unchanged.

The native multiplayer protocol remains **52** (same as v30);
a v31 host can still transfer APK updates to v23+ clients that
already have the LAN update feature. Everyone must run a compatible
native multiplayer version to join gameplay; the updater does not
downgrade newer guests.

All APK upgrades must use Android **Update**, never Uninstall; normal
in-place updates keep `save/u` profiles and `save/z` checkpoints.

## Reproduction and validation

```sh
python3 -m unittest -v tools.test_lan_apk_update
javac -d /tmp/halo-lan-v31 \
  port/android/app/src/main/java/com/halo/decomp/LanUpdateProtocol.java \
  port/android/app/src/main/java/com/halo/decomp/LanUpdateSocket.java \
  tools/lan/LanUpdateProtocolTest.java \
  port/android/app/src/main/java/com/halo/decomp/HaloDiagnostics.java \
  tools/lan/HaloDiagnosticsTest.java
java -cp /tmp/halo-lan-v31 com.halo.decomp.LanUpdateProtocolTest
java -cp /tmp/halo-lan-v31 com.halo.decomp.HaloDiagnosticsTest
```

The pure JVM protocol tests cover private subnets, /32 hotspot fallback,
rejection of carrier/VPN and adjacent networks, fixed routes,
malformed responses, verified file streaming, 503 socket framing and
user-readable failure classification. Android source tests cover
`singleInstance` rearming, bounded retries, no wildcard listeners,
server status, and guest UI behavior.

**Physical acceptance still required:** host v31 on the hotspot,
friend v23–v30 on Wi-Fi, host opens the game via LAN Host with sharing
enabled, guest enters the host hotspot IP and checks for update.
Test a same-version guest, an older guest, a newer guest, busy server
with multiple friends, game process restart, and no-op internet mode.
If a private hotspot interface still cannot bind, have the host
export **SAVE DIAGNOSTIC REPORT** and inspect its LAN update section.
Do not claim confirmed two-phone success solely from simulated tests.

**Immediate workaround:** everyone manually installs the same signed
APK using Android Update. Only the host's *installed version* can be
shared from its live game process.
