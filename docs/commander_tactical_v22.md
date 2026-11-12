# v22: Android host's read-only Commander tactical map candidate

**Status: source candidate only; not built or accepted on Android.** Work is in the
`halo/commander-tactical-v22` worktree, based on exact v21 source `1689116`.
Do not replace the user's v21 APK until native build, package integrity,
physical UI and two-phone LAN regressions have passed.

## First usable slice

The host first-local player in an **opted-in team multiplayer match** can use
the Android touch toolbar's **Map / Close** button. It toggles a read-only
tactical panel showing an XY projection of the active structure BSP and
valid, living **friendly human and bot positions**. The panel uses the BSP's
actual map bounds, not named-map approximations, and is intentionally
read-only. Friendly humans render before friendly bots (at most 48 markers
for bounded frame work). The viewer is differentiated from teammates.

Only when the host has `commander.enabled=true` and
`commander.satellite=true`, and its effective policy permits Commander
in a team multiplayer game, is the button available. The host/local
connection is checked in native C; a joining client cannot enable the map
by changing local configuration, spoofing a Java tap, or receiving ordinary
replicated opponent positions. The code does not grant gameplay privileges
or send new network packets; v21 packet types 96–100 are unchanged.

The touch View queues at most one toggle under an existing mutex. The native
game thread explicitly publishes available/open status and revalidates
eligibility when taking an input. A new game clears panel state. The
touch toolbar is otherwise unchanged and shows no Map button when the
master/child permission is off.

## Deliberate limitations

- **Host-first-player prototype**, NOT a player-selectable commander role.
  No commander election, per-team designation, role transfer or guest commander.
- **Read-only allied map**, NOT a 2142-style playable laptop, full terrain
  texture, camera flyover, UAV drone, enemy spotting, radio or objective orders.
  In particular, enemies are *never* placed on the panel merely because a
  client already has their replicated unit coordinates.
- Shows **the active BSP**, not guaranteed full extents of multi-BSP maps.
  Out-of-bounds and malformed coordinates are skipped. A dead or absent
  friendly unit is not displayed. Map overlays may cover a portion of
  gameplay while open; user can close it immediately.
- Android touch only. Desktop and Web source compile the no-op path but have
  no Commander toggle. No new menu settings, permissions or wire protocol.
- Has **no physical Android validation** yet. Source C policy and wiring
  tests are not evidence of rendered graphics, speaker operation, LAN
  correctness or performance at 127 bots.

## Verification and next qualification

```sh
CC=cc python3 -m unittest -v \
  tools.test_commander_tactical \
  tools.test_commander_feature_policy \
  tools.test_lobby_bot_count
git diff --check
```

The isolated Android-only CI gate includes
`python3 tools/test_commander_tactical.py -v` before native compilation,
but this candidate has not been pushed or spent GitHub Actions.

Before a new APK: isolated native Android guest+host and Java build,
inspect linked host imports, smoke test the actual Map button on phone,
verify side effects on touch controls and the HUD, check 0/1/4/16/48+
allied bots on a map, a map with degenerate or multiple BSP bounds, and
a human host + human joiner. Confirm guests never see the Map button,
team switches update friendly markers, campaign/co-op and FFA hide it,
and disabled Commander remains exactly like v21.

After that, build the manual host-selected commander role with salted
player identity, one role per team, authenticated reliable replication,
reconnect/death/team-change revocation and role transfer. Only then add
host-timed, team-scoped UAV enemy intelligence and attack/defend orders.
Do not reuse personal five-kill recon as a team radar or introduce
client-supplied enemy coordinates.
