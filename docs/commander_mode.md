# Independent Commander Mode and Killstreak Rewards

These are TWO separate multiplayer feature modes, not nested settings.

- **2142 COMMANDER: OFF / OPTIONS** reserves controls for Satellite Track, UAV recon,
  spotting, attack/defend orders and command radio. Team multiplayer only.
- **KILLSTREAK REWARDS: OFF / OPTIONS** enables the powerup slice below in
  team games and free-for-all, without requiring a designated commander.

Both master switches are OFF by default. Their preferences are independent.

## Options

| Option | Native key | Default |
| --- | --- | --- |
| 2142 Commander master | commander.enabled | false |
| Satellite Track | commander.satellite | true |
| UAV | commander.uav | true |
| Enemy spotting | commander.spotting | true |
| Attack/defend orders | commander.orders | true |
| Commander radio | commander.announcements | true |
| Individual Killstreak Rewards master | killstreak.enabled | false |
| Orbital strike reward | killstreak.orbital | true |
| Personal/strike radio | killstreak.announcements | true |

A disabled master suppresses its children WITHOUT erasing their selections.
Both ON enables both groups. Turning either OFF leaves the other alone.
Orbital requires Killstreak Rewards ON, not Commander Mode ON.

The Android host launcher displays both collapsible sections in offline
or LAN-host modes. Each section is written atomically to its own part of
config.toml. Neither changes network addresses, bot counts, game assets or
saved games. For guests and standard launches, local masters are cleared.
Old v14 commander.killstreaks and commander.orbital keys, if retained in an
existing config, are inert legacy entries and grant no rewards.

## Network trust boundary

The native host sends one reliable 12-byte packet (type 96) with a combined
16-bit permission mask. Client permissions default zero, are reset per game,
and only a correctly sized authenticated host packet over the reliable
connection can update them. Unknown bits, client-origin messages, wrong
directions and malformed frames are rejected. Local client settings do not
grant advantages.

- Commander bits: effective only in team multiplayer when its own master
  is enabled.
- Killstreak bits: effective in team or FFA multiplayer when its own master
  is enabled, independently of Commander.
- Campaign and co-op: neither group permitted.
- Future action handlers must ALSO validate host-side player role,
  kill counts, target coordinates and cooldowns before awarding anything.

## First gameplay slice (v16)

Enable the existing **Killstreak Rewards** master on the offline multiplayer
or LAN host. Commander can remain OFF. Only a local game or network server
can grant effects; even a client with the host's enabled policy cannot grant
its own rewards. The existing multiplayer policy also covers internet hosts;
this slice does not change that v15 policy. Internet gameplay is unqualified.

- At 3 credited enemy kills (including bots) in one life: real active camouflage and an
  attempt to charge the earner's overshield through the stock engine APIs.
- At 5: refresh camouflage to at least 900 ticks (30 simulation seconds) and
  attempt the overshield pickup again. Kills 6 and above grant nothing else.
- No team kills, suicides, assists or synthetic bot earners advance this
  separate counter. Human kills of enemy bots DO count, including in offline
  and LAN bot matches. Environmental deaths grant no reward.
- The counter starts when enabled, in any multiplayer gametype, team or FFA;
  campaign/co-op, clients and film playback cannot award effects. No unit,
  a dead unit or a departed player cannot earn a pending reward for respawn.
- Death, disconnect, respawn/unit replacement, match end and disabling the
  master reset eligibility. Full salted player/unit indices prevent slot
  reuse from transferring a streak. State is transient, outside save data.

Camouflage uses `player_handle_powerup_minor(..., 900)`, which preserves any
longer normal pickup timer. Normal engine ticks expire the effect; five kills
refresh it rather than adding another 30 seconds. Cleanup removes camouflage
only when this module owns it on the same player and unit. Accepted normal
powerup calls relinquish that ownership. If a reward extends an existing
normal camouflage timer, cleanup leaves that combined timer alone; the
reward extension remains bounded to 900 ticks from the award. Pausing the
simulation pauses its timer. Standard map pickups and permanent gametype
camouflage are not claimed as reward-owned effects.

**Overshield is a useful partial implementation, not a timed 30-second buff.**
`object_double_charge_shield` starts stock charging only at shield vitality
at or below 1.0. It does not refill an overshield already above 1.0 at the
fifth kill. Stock charging goes up to 3.0; damage consumes it, and stock
multiplayer decay takes roughly 90 seconds from full charge without damage.
Units with zero shield capacity (no-shield gametypes and some Infection
roles) receive camouflage only; starting shield charging on them could
leave the unit stuck in the charging damage state.
There is no reward-only timer or provenance for shield energy. Disabling
rewards or ending a match never subtracts shields or cancels a normal pickup;
the old unit's shields follow ordinary engine lifecycle behavior. A strict
30-second shield cutoff and guaranteed fifth-kill shield refill remain gaps.

Existing distributed unit state already carries the timers, camouflage bits
and shield data from host to clients. No packet, policy bit, network version
or persistent schema changed: peers need no new interpretation or agreement,
only the host runs this optional award logic. Mixed-version behavior and
physical LAN/Android replication still need runtime acceptance.

## Private recon and reward announcements (v17 candidate)

The v16 powerups above are unchanged. At five kills, the earner also gets
900 simulation ticks of red enemy-position markers above living enemy
humans and bots, including enemies behind walls. This state belongs to one
local player, never the team or a spectator. Turning Commander ON later does
not share it. Each viewport checks at most 128 players and draws at most 24
markers, without collision scans. At this cap, the first eligible players
in datum order are drawn; this is not a nearest-target selection.

New optional message 97 is exactly 28 bytes: the distributed header,
absolute player slot and host-salted unit ID, a monotonically increasing award serial, recon end
tick, award (0 revokes, 3 announces, 5 announces and grants recon), and zero
reserved bits. Only the host sends it, reliably to the earner's machine;
the host's own player uses a local callback. No targets or coordinates are
sent. The receiver checks direction, reliable transport, length, header,
type, count, reserved field, award, end time, player-slot-to-local-salt
mapping, unit ownership, life state and host policy. Batches are rejected. Repeats cannot
replay announcements or extend recon. Award age must be below 900 ticks;
up to 15 ticks of client clock lag is allowed without extending the host's
end tick. Revocations use the same private route, only for awarded lives.

No network-version bump: the optional message changes presentation only;
v16's dispatcher ignores unknown type 97, and the authoritative powerups
and existing simulation packets have unchanged semantics. A v16 guest gets
v16 powerups but no v17 recon or reward notices. A v17 guest cannot self-award
when connected to a v16 host. Mixed-version runtime qualification is pending.

`killstreak.announcements` gates the new text and sound, independently of
recon. Packet and kill callbacks only record pending notices. The local HUD
presents them on the render/main thread, at least 90 simulation ticks apart
when both rewards arrive before rendering.

### v18 correction: earned killstreaks are not rapid multikills

The v17 test build used stock Triple Kill and Killing Spree recordings as
reward notifications. That was misleading: a three-kill reward requires
three enemy kills without dying, not three quick kills. v18 removes
the stock sound-tag calls from this reward path. The two new original
callouts are **"Enhancements online"** at three kills and
**"Recon online"** at five. Local HUD text uses those phrases as well.

On Android the native ILP32 game calls a small host import that invokes
HaloActivity on the UI thread. It uses the phone's TextToSpeech engine and
selects only a locally installed English voice requiring no network.
Without such a voice or an installed speech service, the HUD remains
text-only. The resulting sound depends on the user's offline voice engine;
it is synthesized speech, not a recorded military voice artist.
No remote TTS call or third-party voice audio is bundled.

Desktop and Web currently show the accurate HUD messages only. Split-screen
audio stays disabled because the output device mix is shared, although
per-player recon and text remain private. The original Halo announcer can
still announce a genuine rapid multikill as usual; only the separate
reward path no longer triggers those clips. Pausing the Activity stops
the speech service and pending notices are cancelled when permission or
the life ends. An already-playing short phrase cannot be retroactively
unspoken at the exact moment of death.

### v19 audio reliability correction

Physical v18 testing confirmed the personal five-kill red markers but no
audible reward callouts. The Android TTS engine's installed offline voice
availability could not be validated from the game's sandboxed logs, so
we eliminated the TTS dependency rather than silently using network speech.

Android v19 bundles **two original, locally synthesized mono PCM WAV clips**
in its app resources: "Enhancements online" and "Recon online". They play
using Android SoundPool through the same recipient-private, host-validated
JNI callout path. Audio was synthesized locally from those original words
using eSpeak NG 1.51 (en-us male), 22,050-Hz PCM16, with a deterministic
300-3,000-Hz radio bandpass, quiet hiss and short fade. No stock Halo
audio was sampled or shipped. The clips are roughly 86 and 67 KiB.
Playback is asynchronous, fails silently to HUD text if a sample cannot
load, stops on Activity pause, and releases all audio resources on destroy.
No new permissions, downloads or cloud services are required. Actual
Android speaker output is subject to physical testing; voice quality is
synthesized and brief rather than professional radio voice acting.

### v20 user-approved British woman callouts

The user disliked v19's robotic eSpeak audio and selected the British
English woman voice. A punctuation-only attempt to repair an apparent
extra "s" in "Recon online" did not improve it. The user approved the
replacement wording "Recon active".

Both v20 Android PCM WAV assets were split losslessly out of the exact
approved Halo-Voice-Preview-English-Woman-Recon-Active.wav preview.
The first says "Enhancements online" (unchanged), and the second says
"Recon active". Original approved preview SHA-256 is:
cdbfa05e1484cb91074de47c7f51d9e15b6224ec4bdcdff8ad5e2341bf8e1a01.
The concatenation of the two WAV PCM payloads has SHA-256:
69870492579079d2cadf1c52df7bbc242cdc68db4705cbd36e3a0a286aae06b6.
They are 24 kHz mono PCM16, with over 280 ms lead and 250 ms tail
silence. Loaded through v19 Android SoundPool, no network or model
download. Five-kill HUD text says "Recon active (30s)".
No multiplayer protocol or gameplay change. The full speech model and
voice weights remain on Forge scratch and are not in the APK.
Android speaker playback still needs physical qualification.

Death, changed salted player/unit, disconnect, match end, map reset and
master OFF cancel recon and pending notices. Normal full-spectrum vision
pickups and the Commander mask are not repurposed. No persistence changes.

Known acceptance gaps: no native link, Android/Web build, rendered game,
physical audio or LAN session has been qualified for this candidate.
Host and guest may assign different salted player IDs for the same absolute
player slot. The receiver resolves the authenticated host slot to its own
full local datum via distributed_player_from_byte, then validates the current
view's ownership and the replicated unit. The harness tests a differently
salted guest player. Join-in-progress and reconnect still require real LAN
qualification; missing client unit replicas and tick skew can cause an
award to be dropped rather than deferred.

Run `python3 tools/test_killstreak_recon.py -v` for the real recon module,
audio helper and marker function compiled in a fake world, including packet
rejection, per-viewport isolation, expiration, lifecycle, capped rendering
and deliberate faulty variants. The Android-only source gate runs this
before native compilation; editing that gate does not trigger Actions.

## Desired mechanics, still future gameplay work

Commander: one host-designated commander per team, interactive tactical
laptop, commander-only full scan, team-shared area UAV and spotted markers,
attack/defend orders for human squads and bot groups.

Individual human killstreaks:
- 3 kills without dying: camouflage and overshield, 30 seconds.
- 5 kills: guaranteed overshield refill remains future work; private enemy
  HUD tracking is implemented by the v17 candidate above.
- 10 kills: one manually targeted orbital strike.
- Only the earner hears "Orbital strike authorized."
- Everyone hears "Orbital bombardment incoming" after target confirmation.
- Death resets the streak; only the host validates rewards and damage.
- No third-party copyrighted voice samples; any voice recording must be
  original.

The tactical laptop, spotting, 10-kill orbital strike, Commander radio and
NPC objective orders are NOT implemented. Orbital and Commander radio
options remain reserved controls. Personal reward announcements now use
Android offline speech as described above. The settings UI and its v15 wording are
unchanged; visible checkboxes are not evidence that future abilities work.

## Acceptance checks

Run `python3 -m unittest -v tools.test_killstreak_rewards` for source-backed C
checks of the reward module, engine powerup helpers and real statistics kill
path in a fake world, including deliberate faults that must fail. This is
not a game binary test. Build generators already include every port game
`.c` file on Linux, Windows, Android and Web.


- All 512 combinations of feature bits independently gated in C tests.
- Separate Java config tests verify atomic persistence and no corruption
  of the other section or network/bot fields.
- Cross-section test verifies each master can be enabled and disabled
  independently, with both selected or both disabled.
- Source tests validate reliable host-only mask delivery and rejection of
  client attempts to self-authorize.
- Real LAN host/guest and Android UI tests are pending; the actual
  battlefield systems each need their own gameplay qualification.
