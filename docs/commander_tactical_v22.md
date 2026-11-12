# Halo v22 Commander role and allied tactical map (candidate)

**Status:** source candidate only. The running/installed v21 APK is unchanged.
Branch `halo/commander-role-v22` extends clean v22 map baseline `546b489`.
Native Android build, device interaction, and two-phone LAN are not yet
qualified. No cinematic map renderer, UAV, orders, or enemy radar is claimed.

## What the v22 candidate does

- A LAN or offline host enables **2142 COMMANDER MODE** and **SATELLITE TRACK**
  from the game's Android launcher before starting a **team multiplayer** match.
  Both remain off unless selected (Commander master default OFF).
- While the match is running, the host's first Android player sees an **Assign**
  touch toolbar button. Tapping cycles through human players (no bots),
  then **Commander cleared** after the last choice. The host sees the selected
  player's actual in-game roster name. There is exactly **one** commander
  assignment across the whole match in this first slice, not one per team.
- The assigned player's phone sees **Map / Close**. Only that human sees the
  read-only tactical overview of the active BSP's XY bounds and up to 48
  living allied humans and bots. No client can grant itself a role or reveal
  enemies by changing its local config. A guest selected as commander gets
  the map when the host's reliable role packet arrives.
- Assignment persists through a normal death and respawn, and is revoked
  when the player quits, switches team or changes roster name, or the host
  turns Commander off. Assigning again replaces the previous role.
- Campaign, co-op, FFA, internet hosts without explicit local opt-in,
  non-Android ports and unassigned clients gain **no** tactical map.

## Network/security policy

The existing authenticated, reliable, host-to-client Commander settings
packet (type 96) now carries the selection, with strict new 44-byte length.
It contains the effective feature bitmask, an absolute player slot, team,
12 UTF-16 roster code units and a monotonically increasing packet serial.
The client's differently salted player datum is matched via current roster
identity and local ownership, not a guessed salt. All unspecified flags,
reserved fields, invalid teams/slots, empty-name misuse, untrusted machines,
unreliable frames and stale/replayed serials are rejected.

This changes wire compatibility: **network protocol 46 -> 47**. Every
participating LAN device needs the same new APK. The killstreak, 10-kill
orbital request/warning packet IDs 97-100 are preserved. Commander radio,
host-timed UAV, enemy spotting and attack/defend orders still have no active
gameplay implementations. A one-role model is not a complete Battlefield
2142 commander hierarchy.

## Current limits and acceptance

The role picker is an in-game cycle, not a roster dropdown or selection from
the pregame lobby. Only each Android phone's first local viewport can open
the map; local splitscreen secondary controllers are not qualified.
The overview uses the **active** BSP, which might not cover all of a
multi-BSP map. No enemy position appears merely because a client has its
ordinary replicated unit. The source restricts work to bounded checks
but needs frame-time measurement with 16/48+ friendly bots.

Source checks:

```sh
CC=cc python3 -m unittest -v \
  tools.test_commander_roles \
  tools.test_commander_tactical \
  tools.test_commander_feature_policy \
  tools.test_lobby_bot_count \
  tools.test_killstreak_rewards \
  tools.test_killstreak_recon \
  tools.test_killstreak_orbital
git diff --check
```

The Android-only workflow runs these source gates before native compilation.
This does **not** establish an APK is installable. Final qualification needs
an isolated exact-source Android guest+host build with a permanent upgrade
signature, matching package identity/version code, and physical multiplayer
testing. Verify host and guest disagreeing datums, guest role handoff, death,
new match, team switch, late join, commander OFF, private recon, all four
audio clips and game-over reconnect. Never delete Android app storage or
uninstall v21 for testing.

## Next milestone

After role assignment and host/guest map controls pass, add a true
per-team elected commander, a tablet-sized interactive map with touch
pan/zoom and objective points, host-timed area UAV and spotted enemies
only when validated by the host, then commands for bots and teammates.
Every networked action needs sender ownership and bounded validation.
