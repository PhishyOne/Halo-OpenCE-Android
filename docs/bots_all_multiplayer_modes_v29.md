# Halo Android v29: bot participation in every stock multiplayer engine

**Status:** Source candidate based on v28 at
`f29714a155a3985d5e715951ba52ccff8a2d469d`.
The v28 phone APK and all saved profiles remain intact until the signed
v29 build is verified. Physical Android LAN acceptance remains necessary.

## Five stock modes

- **Slayer** (including Team Slayer and its special variants): Existing
  aiming, pathing, inventory, grenades, melee, team rules and host-authored
  kill scoring are unchanged.
- **Capture the Flag:** Existing team-based flag offense and defense,
  carrying the enemy flag back home and normal pickup controls.
- **King of the Hill:** Existing active hill and Multi-Hill distribution;
  score only through Halo's own hill capture rules.
- **Oddball** (including Team Oddball and alternate ball modes): The
  actual Oddball engine publishes live ball locations through
  `game_engine_set_goal_position`. Bots distribute among currently
  active ball goals, chase a carried ball and use the usual nearby weapon
  pickup input to claim a loose ball. When holding one, they abandon
  the pursuit and travel toward map-provided roaming points rather than
  chasing their own position. The engine still decides pickups,
  possession and scoring. Magic/Terminator variants use the engine's
  authoritative `player_has_ball()` carrier state, not a guessed item.
- **Race** (normal sequential checkpoints, any-order and Flag Rally):
  A new **read-only** game-engine API exposes a bounded bitset of
  checkpoints this *specific player* is eligible to touch, delegating
  to the exact `race_goal_matches_player` / `can_touch_team` scoring
  rules. The bot goal policy picks a legal active checkpoint, looks up
  its current world position, and uses the existing foot pathfinder.
  Only a real collision with the checkpoint can score; AI doesn't
  forge lap counts or checkpoint events.

The system only creates synthetic bots on the actual local/LAN host,
only in its pregame lobby, and only in the five stock game-engine types.
Other/unknown engine types still fail closed. CTF/King/Oddball/Race
require `bots.objective_modes` (already on in the Android launcher);
Slayer does not. This option applies to offline and LAN games, not
internet servers or campaign co-op.

## Known limitations

**Race bots do not drive vehicles yet.** This build intentionally
disables race-specific vehicle-seat seeking because the existing
seat AI handles passengers/gunners but has no navigation or steering
for drivers. Race bots run checkpoints **on foot** wherever the map's
walkable surfaces reach them. Vehicle-only routes, vertical shortcuts,
and inaccessible gates may require driver/vehicle AI before bots can
complete laps. Other game modes keep vehicle-seat behavior unchanged.

Oddball carriers currently use safe roaming-point evasion, not
coordinated teammate escort tactics. Pathing uses a limited per-tick
search budget; on very crowded maps some bots may still take poor
routes. No new scoring rules, multiplayer packet types, imported maps,
assets, campaign saves or settings are modified.

The high-count LAN feature from v28 remains optional: up to **64
bots** and 128 total player slots. Large matches remain a host-phone
performance and hotspot stress test; start at 16–32 before trying 64.

## Protocol and tests

Since bots now spawn and perform host-simulated objectives in
previously unsupported modes, network protocol **50 → 51**.
All humans in a LAN match must install v29. The v23+ hotspot APK
updater can offer v29 from the hosting phone. An Android in-place
Update preserves `save/u` profiles and `save/z` checkpoints.

```sh
PATH=/home/phishyone/.local/bin:$PATH CC=cc python3 -m unittest -q \
  tools.test_bot_objective_policy \
  tools.test_all_multiplayer_modes \
  tools.test_lan_64_bot_capacity \
  tools.test_lobby_bot_count \
  tools.test_campaign_save_backup
```

The real C selector policy is compiled and checked with negative
and boundary cases for Oddball (up to 16 balls) and Race (up to 32
checkpoints, including index 31). Source checks verify the host-only
roster gate, unmodified CTF/King selectors and the read-only race
scoring API. The Android-only build workflow adds these before
native compilation and signing.

## Physical acceptance with multiple players

1. On a large LAN map with 8–16 bots and two or more humans, test
   each of the five modes. Confirm a full roster, no midmatch
   player injection and no client-side extra AI.
2. Oddball: watch bots pick up the ball, chase the human carrier,
   run away when they carry it, drop the ball on death, and score
   using normal rules. Test multi-ball and Team Oddball separately.
3. Race: on a walkable circuit, verify each bot follows valid next
   flags, advances checkpoints and accumulates laps. Test Normal,
   Any Order and Flag Rally. Record maps with inaccessible on-foot
   flags for follow-up driving AI.
4. Check CTF flag-return behavior, Multi-Hill scoring, Team Slayer
   balance, the existing v28 64-bot caps and bot-count strip.
5. Verify v29 in-place installs without losing campaign progress
   or restoring from a backup, and that the hotspot updater still
   transfers a compatible APK to joining phones.

Source tests cannot substitute for this on-device evidence.
