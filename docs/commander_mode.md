# Independent Commander Mode and Killstreak Rewards

These are TWO separate multiplayer feature modes, not nested settings.

- **2142 COMMANDER: OFF / OPTIONS** supports Satellite Track, UAV recon,
  spotting, attack/defend orders and command radio. Team multiplayer only.
- **KILLSTREAK REWARDS: OFF / OPTIONS** supports human-earned rewards in
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

IMPORTANT: This PR implements switches, persistence and network policy
only. The tactical laptop, spotting, kill rewards, 3D strike, HUD tracking,
sounds and NPC objective orders are NOT yet playable. Never claim they are
working because a configuration checkbox is visible.

## Acceptance checks

- All 512 combinations of feature bits independently gated in C tests.
- Separate Java config tests verify atomic persistence and no corruption
  of the other section or network/bot fields.
- Cross-section test verifies each master can be enabled and disabled
  independently, with both selected or both disabled.
- Source tests validate reliable host-only mask delivery and rejection of
  client attempts to self-authorize.
- Real LAN host/guest and Android UI tests are pending; the actual
  battlefield systems each need their own gameplay qualification.
