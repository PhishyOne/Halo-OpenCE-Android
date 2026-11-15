# Halo Android v30: bounded, opt-in bot ground driving

**Source candidate**, based on v29 `ac6253166b7d7c77fb86a36cc0a30830101a35c8`.
No v29 APK or live app data was modified. The signed Android build and
actual two-phone vehicle physics/play tests are required before declaring
the behavior playable.

## The existing engine is the actuator

The native Halo code already copies a driver's normal `player_action`
facing vector and throttle to the parent vehicle
(`units.c`), whose own `vehicles.c` computes steering, wheel/tread
physics, suspension, damage and collisions. The new bot driver uses this
same path on the authoritative host and the existing bot action relay to
LAN guests. It **does not teleport vehicles**, alter object velocities
or bypass unit seat entry, and it never controls a human's player action.

The Android **LAN → HOST** panel now has
**BOTS DRIVE GROUND VEHICLES (EXPERIMENTAL)**, unchecked by default.
The setting is `[bots] vehicle_driving = false` until the host
explicitly opts in; joining clients and internet sessions always
disable it. Existing users' old settings and the `save/u` and
`save/z` campaign files are unchanged.

## Driver behavior

- The engine's own animation / nearby-Action prompt seats a bot in an
  empty driver seat. Any human occupant blocks it. Warthog, Ghost and
  Scorpion **ground vehicles** are supported through a conservative
  tag-name allowlist. Banshee, Pelican and custom flying craft are not.
- **Up to six simultaneous driver bots** can seek a driver seat.
  A separate per-vehicle reservation prevents multiple bots racing
  for the same wheel. Other bots can remain normal passengers/gunners,
  and no human is ever evicted. A subset of objective bots can seek
  cars without redirecting the other players away from their goals.
- The driver uses normal map roaming points or engine-owned
  CTF/King/Oddball/Race goals. The `bot_driver_steering` pure-C controller
  computes a facing angle and a low forward throttle. Tight turns slow
  the vehicle; distant/invalid/reached destinations neutralize input.
  Driving on a flipped or destroyed vehicle is refused.
- If less than one map unit of forward progress occurs over three
  seconds while the goal is still far away, the driver backs up for
  one second at limited reverse throttle, then retries. The vehicle
  is not teleported or reset. Foot navigation / other modes retain
  the original behavior when driving is disabled.
- Race bots may now use the same ground vehicles if the host enables
  driving. The engine still owns checkpoint/lap validation; vehicle
  movement only gets credit by reaching a legal checkpoint naturally.

## Limitations and acceptance

This is **ground steering**, not an obstacle-aware driving graph:
existing pedestrian waypoints do not always correspond to drivable
roads. Bots may collide with terrain, buildings or other vehicles.
A full 64-bot LAN match is performance-sensitive; start with 4–8 bots
and two human players, with the driver option on. Validate Warthog
turning/acceleration, Ghost and Scorpion handling, gunners firing while
a driver moves, human-entered seats staying intact, and recovery from
collisions. Try up to six drivers only after a small match is stable.

**Banshee flight is a separate project.** It requires vertical pathing,
ground clearance and ceiling collision checks, roll/pitch/stall
control and physics-specific tests. Do not enable aerial AI by matching
a Banshee name in this ground driver.

**Blood Gulch vehicle spawning:** Halo's default vehicle preset filters
Banshees; the in-game gametype **Vehicles → PC** preset admits the map's
placed vehicle types. The map must actually contain a Banshee placement,
so the preset isn't a promise one will spawn. Avoid forcing all maps to
spawn Banshees or altering existing gametype variants globally.

**Beach Assault blue screen:** The source map
`beach_assault.map` found on Forge has a structurally valid 609 Custom
Edition cache header and tag-data bounds. This does not establish that
Android can render the map; no actual error log or screenshot of its
renderer is available. Do not publish a blind map or shader patch.
Keep it out of LAN rotation until the game's **Save Diagnostics**
report identifies the failure.

## Tests

```sh
PATH=/home/phishyone/.local/bin:$PATH CC=cc \
  python3 tools/test_bot_vehicle_flip.py
PATH=/home/phishyone/.local/bin:$PATH CC=cc \
  python3 -m unittest -v tools.test_bot_vehicle_driving
JDK=/mnt/nomatoka-terrain-scratch/halo-opence-android/toolchains/jdk/bin
"$JDK/javac" -d /tmp/halo-v30-classes \
  port/android/app/src/main/java/com/halo/decomp/LanConfig.java \
  tools/lan/LanConfigSmokeTest.java
"$JDK/java" -cp /tmp/halo-v30-classes \
  com.halo.decomp.LanConfigSmokeTest
```

The tests run the exact production seat filter, entry action and flip
reservation against a fake world, plus the compiled steering policy,
config role changes, unsafe tags and campaign preservation gates.
They don't prove that a vehicle follows its route smoothly on a phone.

**Network protocol 51 → 52.** Every person in a LAN match needs v30;
the v23+ LAN host APK updater should offer the upgrade. Android's
same-package/same-signer **Update** preserves app data; never Uninstall
as an update workaround.
