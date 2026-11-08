# Experimental player-bot vehicle seats (Android / LAN / offline)

## Scope

This is the first bounded version of vehicle AI. Player-style Spartan bots
look for nearby stationary vehicles, prefer open gunner seats, and try
non-driver passenger seats only when the vehicle has a driver.

The campaign implementation was inspected in source/ai/actions.c:
actor_action_try_to_enter_vehicle and actor_action_handle_vehicle_entry.
They depend on actor-specific squad/encounter orders and are not directly
usable with simulated multiplayer players. The new code reuses the engine
seat-availability and entry-animation tests and the existing map pathfinder.
Actual boarding uses the normal player Action control bit, rather than
calling unit_enter_seat directly, preserving the LAN host action relay.

## Intended behavior

- Off by default. Toggle bots.vehicle_seats on in the Android host launcher
  (or Offline Bot Match options). A joining client or internet-mode launch
  disables vehicle AI, and the LAN host keeps the existing max-16 bot count.
- Scan parked vehicles within 16 world units every few seconds, with a
  preference for gunner seats. Passenger requires an occupied driver seat.
- Never enter driver seats or evict an occupant. Never enter a dead or
  disabled vehicle or a seat whose animation the Spartan cannot play.
- Pathfind to the real seat entrance; only press Action when the normal
  engine interaction result says the intended seat is available.
- Give up a contested/inaccessible seat after 14 seconds, then retry
  after a cooldown rather than circling it forever.
- As a gunner, aim and fire using the parent vehicle's weapon with normal
  existing target selection; disable infantry melee/grenade/strafe inputs
  while mounted. A passenger has no autonomous steering or fire input.
- Log attempted and successful entry for later debug inspection.

## Restrictions and validation requirements

- The code does **not** implement autonomous Warthog, Scorpion, Ghost or
  Banshee driving/flying. Campaign driver motion and navigation live in
  actor_moving.c and require a separate player-controls architecture.
- Gunner shooting is **not yet confirmed on a device**. The parent vehicle's
  weapon must be present for the bot to recognize and fire it.
- No new network messages or version bump: protocol 45 is retained, since
  the existing player control flags and vehicle unit state are reused.
- Sidewinder in the user's existing Xbox disc image has a corrupt source
  compressed stream. Test on a working map such as Blood Gulch.
- This branch builds on Alpha 4's guest postgame-continuity candidate,
  which itself still awaits live retesting.
- Never merge on source tests alone. Require independent exact-head Android
  compilation/signing, then both offline and two-phone playtests.

## Playtest

1. Start a Blood Gulch Slayer match with 4-8 bots on Normal and enable
   the experimental vehicle seats setting. See whether a bot goes to a
   stationary Warthog and boards the turret.
2. Check that the gunner turns, fires at enemies and causes actual damage.
   Next, sit in the Warthog driver's seat yourself and see whether another
   bot boards as gunner/passenger. The bot should never take the wheel.
3. Repeat with two devices on protocol 45. Confirm the guest sees the
   same seat occupation, turret animation, projectiles and kill records.
4. If a bot refuses to board, preserve debug.txt with the map, bot count
   and the requested seat. The bots vehicle log entries distinguish
   searching failure, movement failure, entry failure and turret firing.

## Source tests

- tools/harness/tests/test_bot_vehicle_seats.py extracts the actual
  production seat filter and player Action function. Thirteen cases cover
  valid gunner/passenger, driver/occupied/bad/disabled seats, missing
  animation and correct/incorrect/cooldown Action behavior. Two mutation
  controls catch intentional safety bugs.
- tools/lan/LanConfigSmokeTest.java validates the boolean option is atomic
  and preserves original offline and LAN settings.
