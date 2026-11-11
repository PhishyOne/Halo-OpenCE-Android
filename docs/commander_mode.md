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

## Desired mechanics, still future gameplay work

Commander: one host-designated commander per team, interactive tactical
laptop, commander-only full scan, team-shared area UAV and spotted markers,
attack/defend orders for human squads and bot groups.

Individual human killstreaks:
- 3 kills without dying: camouflage and overshield, 30 seconds.
- 5 kills: refresh both and add 30 seconds of enemy HUD tracking.
- 10 kills: one manually targeted orbital strike.
- Only the earner hears "Orbital strike authorized."
- Everyone hears "Orbital bombardment incoming" after target confirmation.
- Death resets the streak; only the host validates rewards and damage.
- No third-party copyrighted voice samples; any voice recording must be
  original.

The tactical laptop, spotting, 5-kill enemy recon HUD, 10-kill orbital
strike, reward announcements, sounds and NPC objective orders are NOT
implemented. Existing orbital/radio options are reserved policy controls,
not working gameplay. The settings UI and its v15 wording are unchanged.
Only the bounded powerup slice above has gameplay code; visible checkboxes
are not evidence that other abilities work.

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
