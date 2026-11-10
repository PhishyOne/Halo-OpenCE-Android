# Commander Mode (2142-inspired, opt-in)

This work deliberately separates **permission controls** from gameplay
abilities. The first phase implements the full host-authoritative settings
model, not a pretend working laptop or unfinished killstreak effects.

## Controls

In Halo Android's launcher, the hosting player can expand
**COMMANDER MODE: OFF / OPTIONS**. The entire commander feature family is
**off by default**. Activating the master switch permits these optional
subfeatures, each of which the host may independently uncheck:

| Switch | Config key | Default |
| --- | --- | --- |
| Master Commander Mode | commander.enabled | false |
| Satellite Track (commander-only) | commander.satellite | true |
| UAV reconnaissance (team-shared) | commander.uav | true |
| Enemy spotting (team-shared temporary markers) | commander.spotting | true |
| Attack/defend orders | commander.orders | true |
| Killstreak rewards | commander.killstreaks | true |
| Orbital bombardment | commander.orbital | true |
| Radio announcements | commander.announcements | true |

The subfeature defaults become effective **only if** the master is enabled.
Orbital is additionally disabled if killstreaks are off; its checkbox
preference is preserved so it can be restored without re-entering settings.
Features apply only to **team multiplayer**, never free-for-all, ordinary
campaign, or network co-op. Turning off Commander Mode restores ordinary
Halo play. The host can configure these settings for its offline and
LAN-hosted matches. A LAN guest cannot enable or override them.

The Android launcher writes only the commander config section through the
existing game-data directory. No maps, saves, LAN addresses or bot counts
are altered. The options survive restarts; a guest/standard launch clears
only the local master bit and the native client never uses its own settings
as a source of authority.

## Network authority

The native game sends a 12-byte commander policy message, message type 96.
Only the authoritative host sends the **effective bitmask**, over reliable
game messages, once on load/rejoin and when it changes. An observing client
starts with zero permissions, resets on a new game, rejects malformed
packets, unreliable datagrams, unknown bits, wrong sender/direction, and
never evaluates its own TOML to grant powers. Every future tactical map,
UAV, spotting, order, killstreak, orbital and radio gameplay action must
check the effective policy before executing; the host additionally
validates the sender, player role, cooldown and position.

Current phase does **not** yet create commander roles, render a laptop,
report enemy positions, award power-ups, broadcast spoken lines, issue
squad orders or fire orbital weapons. Do not mistake visible settings
for working game abilities.

## Intended Battlefield-2142-style gameplay

- One commander **per team**, host-designated and able to resign.
- A tactical laptop/map with team positions, flags/hills, and squad orders.
- Satellite Track is a commander-only whole-battlefield scan.
- UAV is a localized scan revealed to the commander's team.
- A hostile shot or explicitly spotted is temporarily tracked for its
  relevant team, rather than permanently exposing every player.
- The commander may order squads and bot groups to attack or defend
  objectives. Bots need formation/spread/flank logic before large tests.
- Optional human killstreaks:
  - 3 kills without dying: 30 seconds of active camouflage **and** overshield.
  - 5 kills: refresh those two and add 30 seconds of enemy HUD tracking.
  - 10 kills: one manually designated orbital bombardment.
- Personal earning call: **"Orbital strike authorized."** (only the
  player who earned the strike hears it).
- Confirmed strike call: **"Orbital bombardment incoming."** (all players
  hear it via non-positional announcement audio).
- Designated strike locations must be validated by the host, with an
  advance warning, safe map bounds, cooldowns and friendly-fire rules.

These mechanics are future implementation targets, not bundled content in
the current settings-only build. Original custom voice recordings must be
supplied or produced for each phone; no 2142 copyrighted game audio is used.

## Acceptance before enabling by default

1. Off by default after a clean install and with all subfeatures selected.
2. Disable master on host: no team capabilities remain effective for host
   or clients.
3. Disable just UAV/spotting/orders: no corresponding action is accepted,
   while the others remain allowed.
4. Disable killstreaks: orbital power is also disabled.
5. A guest locally toggles commander settings: host policy is unchanged.
6. Mid-match host changes are transmitted reliably; reconnecting guests
   receive the current mask before any commander capability can be used.
7. Ordinary team Slayer/CTF/King without Commander Mode, free-for-all,
   campaign and co-op retain their current behavior.

Settings-only automated gates cover the policy, persistence and reliable
host/client transport. Real gameplay acceptance begins after the command
role, tactical map and support systems are implemented.
