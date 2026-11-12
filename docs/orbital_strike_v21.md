# v21 targeted orbital strike source candidate

This change builds on v20 commit `296543e5d1362dd9991cd066d7709dceb549aa09`.
It implements host-authorized designation and host-only damage in source.
Local fake-world tests are not Android, map, audio-speaker or LAN acceptance.

## Controls and behavior

Enable the independent `killstreak.enabled` and `killstreak.orbital` settings
on the host. Commander is neither required nor granted. Both master switches
retain their OFF defaults. The existing orbital child preference remains
true, ineffective while the killstreak master is OFF; no configuration or
launcher layout is migrated.

Only living human players earn a strike. Ten credited enemy kills in one
life grant one entitlement; enemy humans and bots count. Suicides, friendly
fire and assists do not. The counter saturates at ten. The original 3/5
camouflage, stock overshield behavior and private recon are preserved.

The earner sees `ORBITAL READY: HOLD ZOOM + TAP FLASHLIGHT`. In first person,
aim the crosshair at ground, hold the existing Zoom control (11), then tap
Flashlight (5). A held flashlight is not a second press. The existing zoom
and flashlight actions still operate; no button layouts are changed.
Scripted/spectator cameras, inhibited input, dead units, sky and steep walls
cannot designate. The current observer camera supplies the finite ray.

The client sends a reliable request only after its local ground check.
An accepted designation consumes the host entitlement once. A rejected
request consumes nothing; if no incoming warning reaches the client,
the DESIGNATION SENT HUD message clears after 90 simulation ticks and the
player can aim and deliberately tap again. There is no automatic resend
and no immediate host denial acknowledgement. Host acceptance still rejects
duplicate requests and enforces at most one geometry check per player
per simulation tick. Moving during network transit can cause a rejection.

On host acceptance, every current client receives a reliable warning and
actual designated point. The HUD says `Orbital bombardment incoming` once,
including for dead local players. After 100 simulation ticks (about 3.33
seconds), the host performs one lethal impact within 14 metres, credits the
earner via `damage_kill_object_for_player`, and lets normal damage replication
carry the result. Terrain between the center and the victim's head blocks
it. It considers at most 128 current player slots (127 possible enemy bots),
never respawn records. Allies and the owner are excluded. There are no
cinematic VFX, pulse pattern or world-space targeting marker in this milestone.
The public HUD notice is the visual warning.

Distances use the engine HUD's 3.048 metres per world unit: the ray/range
limit is 128 / 3.048 units, radius 14 / 3.048 units. The target is a walkable
structure surface, not a vehicle, scenery object or water plane. The
fallback kill helper now checks for an absent falling-damage block or tag;
if the map has none, the accepted strike safely causes no damage. Such maps
are not qualified as playable orbital maps until a supported damage tag is
confirmed. Normal maps use the existing instant-kill flag and attribution.

Death, reconnect/player or unit salt replacement, ownership/team change,
match end, or disabling either effective feature cancels entitlement and
pending impact. Death during the warning cancels the impact even though the
public warning has already played. There is no persistent entitlement.
At most four impacts execute per update to bound simultaneous strike work;
further due impacts wait until a subsequent update.

## Wire and authority

Protocol version rises from 45 to 46 because peers must agree on designation
and damage. **v20 clients cannot join v21 games, even with orbital OFF.**
Upgrade all LAN peers together. Packets 96 (Commander) and 97 (private 3/5
recon, 28 bytes) are unchanged. No private recon target list is shared.

| Type | Direction | Kind | Purpose |
| --- | --- | --- | --- |
| 98 | host to earner's machine | 0 or 1 | revoke or private authorization |
| 99 | authenticated client stream to host | 2 | target request |
| 100 | host to all current clients | 3 | incoming warning and designated point |

All three messages are exactly 36 bytes: distributed header (8), absolute
player slot (4), full salted unit ID (4), host-issued serial (4), XYZ float
point (12), kind (2), zero reserved word (2). Private messages carry a zero
point. Header class 2, exact encoded size, zero flags, count 1, correct kind,
reliable transport and non-batched delivery are mandatory. Native/Android
ILP32 size is statically asserted. No new Android host import is needed.

The host checks the stream's machine index, player machine, authenticated
machine player list, full life identity, independent policy, in-play state,
actual reward counter at ten, host entitlement serial and unused state. A
local host designation uses the same validator, with an explicit local
viewport ownership check. Serial values are not reused across maps within
the process; wrap fails closed. They are replay guards, not secret tokens
or a replacement for transport ownership authentication.

Coordinates reject NaN/infinity by float bit checks, must lie within bounded
world coordinates and 128 metres of the living owner's head. The host
rechecks nearby structure ground and line of sight from its own unit head;
it does not trust a client collision report. At most one geometry validation
per entitled slot per simulation tick limits invalid-request work. An
accepted serial is consumed before broadcasting. Clients cannot grant an
award, broadcast a warning, or apply impact damage through these handlers.
Host-to-client presentation also deduplicates serials and rejects old lives.
The protocol inherits the existing LAN reliable connection's authentication;
this does not add cryptographic LAN identities or protect against a malicious
host. Clients may choose any validated reachable ground point, not only the
host's estimate of their exact crosshair direction.

## Original audio

Only the earner hears `Orbital strike authorized.` at ten. It never triggers
automatic bombardment. Public `Orbital bombardment incoming` occurs only
after an accepted designation. Sound calls occur in HUD presentation, not
packet handlers or kill callbacks. `killstreak.announcements` still controls
radio; the readiness and public safety HUD notices remain visible.

Android uses four asynchronously loaded SoundPool resources with existing
pause/destroy/load guards, one active stream and no downloads. The v20 3/5
files retain their exact bytes. Other platforms use text. Private audio is
suppressed with multiple local viewports; the public cue plays once from the
first viewport. Concurrent radio events can interrupt the previous sample,
as with the existing single-stream v20 callouts. Muted/unavailable audio,
a hidden HUD, background activity, very late warnings and missing samples
can prevent an audible cue; physical speaker tests remain required.

The two original British English woman clips were synthesized on Forge
for this Halo feature before the isolated source edit; they are mono
PCM16 at 24 kHz, not recordings supplied by the user:

| File | SHA-256 |
| --- | --- |
| `halo_reward_orbital_authorized.wav` | `538b9024ef39a94d6f031b1e6a7f9bbfc7b341689d36ee3a8cbbea1d6772b546` |
| `halo_reward_orbital_incoming.wav` | `1155ad38bc3d1f32671159d783e5eb50b0a7be195de5c69f1e0cf19751726315` |

## Local tests and remaining acceptance

Run with a local compiler, without game data or network access:

```sh
CC=gcc python3 -m unittest -v tools.test_killstreak_rewards tools.test_killstreak_recon tools.test_killstreak_orbital tools.test_commander_feature_policy
```

The C harness compiles the actual reward/orbital modules, input chord and
kill helper against a fake world. Negative controls must fail after removing
security/timing/team/range/replay checks. Geometry results are stubbed; this
cannot prove behavior against real BSPs. The Android-only source test gate
in `build.yml` includes the new harness; no Actions run is required or
started by these tests.

Before release, compile with GCC warnings as errors and the native Android
and web toolchains, then qualify real maps, ten-kill earning, death and
post-match cleanup, first-person touch chord, sky rejection, team safety,
kill attribution, covers/radius, 127 bots, simultaneous strikes, two-device
LAN private authorization/public warning/replay handling, and speaker
playback of all four clips. No APK, signing, installed-game, phone, map or
production modifications are part of this source milestone.
