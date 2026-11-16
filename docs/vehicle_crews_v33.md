# Halo Android v33: Warthog crew recruitment and armed ground bots

**Source candidate** from signed, Android-verified v32
`980b79aea0183bdd40a7f33b70ce81b416cb58c8`.

## Field report

On a real hotspot LAN session, bots successfully entered and drove
ground vehicles. Two problems remained:

1. Scorpion drivers did not aim at or fire on enemies.
2. Bots rarely boarded a human-driven Warthog's empty turret.

Beach Assault also still failed to render. No app-owned v32
**SAVE DIAGNOSTIC REPORT** export was available in the accessible
Downloads folder at the start of this patch. Its exact renderer failure
cannot yet be distinguished from an installed-resource mismatch. The
new v32 CE resource audit is retained, not undone.

## Root cause and narrow fixes

### Ground-vehicle firing

The v30/v32 driver controller explicitly wrote zero trigger input and
never acquired enemy targets while seated. Halo's original
`unit_update_driver_and_gunner` logic copies the real driver's throttle
through `UNIT_CONTROL_DRIVER_MASK`, and the real gunner's weapon
controls through `UNIT_CONTROL_GUNNER_MASK`. The Scorpion and Ghost
can expose a seat in which **one bot is both driver and gunner**.

The new host-only vehicle gun path:

- Checks `vehicle->unit.driver_object_index` **and**
  `vehicle->unit.gunner_object_index` both identify the simulated bot.
  Never send gunner controls when a human owns that seat.
- Restricts firing to supported Scorpion/Ghost tag names, intact
  upright vehicles with an actual vehicle weapon.
- Uses the existing enemy-target, team and line-of-sight system
  (`bots_bot_look` / `bots_find_target`). Never invent target objects
  or give a bot an enemy team assignment.
- Turns the normal player's desired weapon aim toward a recently seen
  enemy, while keeping the existing bounded throttle and collision
  physics. Permits a firing pulse only for a front-facing target
  10–75 map units away with safe pitch and a one-second cooldown.
  Halo's weapon determines actual ammunition, reload, damage and
  replication; no direct damage or teleport calls are added.
- Preserves the separate gunner's targeting/weapon controls if a
  human or another Spartan is seated there.

A Scorpion with a different seat layout or missing mounted weapon
will still need further tag-specific work: the owner and weapon gates
fail closed rather than guess.

### Warthog turret recruitment

The v30 seat finder rejected any vehicle travelling faster than
**0.02 in Halo's velocity representation**, effectively ignoring a
gently moving human Warthog. It also returned early from objective selection for
nearly every non-driver bot, so CTF/King/Oddball/Race kept potential
gunners away.

v33 allows a **low-speed (up to 0.12 in the same velocity representation)
human-driven gunner seat** to be considered, but not fast moving cars, moving
driver seats or passenger seats. The engine still requires the
authentic available-seat/entry-animation/nearby-Action prompts.
A friendly human driver adds priority for the empty turret; a bot
refuses a human-driven enemy-team vehicle. Only a subset of
objective-mode bots are eligible to seek a crew seat, leaving most
players to capture flags/hills/balls/checkpoints.

The existing six-driver cap and the normal vehicle flip and
gunner-firing paths are preserved. Bots do not board speeding
vehicles, jump into seats, commandeer humans, or bypass collision.

### Diagnostics

**SAVE DIAGNOSTIC REPORT** now recognizes safe
`bots: ground gunner`, `bots: bot N seeking vehicle`,
`bots: bot N entered vehicle`, and driver-stall lines.
Private IP/email redaction and credential filtering are unchanged.

## Tests and limits

```sh
PATH=/home/phishyone/.local/bin:$PATH CC=cc \
  python3 -m unittest -v tools.test_bot_vehicle_crews \
  tools.test_bot_vehicle_driving \
  tools.test_bot_objective_policy

JDK=/mnt/nomatoka-terrain-scratch/halo-opence-android/toolchains/jdk/bin
"$JDK/javac" -d /tmp/halo-v33-jdk \
  port/android/app/src/main/java/com/halo/decomp/CeResourceAudit.java \
  port/android/app/src/main/java/com/halo/decomp/HaloDiagnostics.java \
  tools/lan/HaloDiagnosticsTest.java
"$JDK/java" -cp /tmp/halo-v33-jdk com.halo.decomp.HaloDiagnosticsTest
```

The test suite compiles the production crew policy and **extracts and
compiles the exact production gun-control function** against a fake
vehicle/player world. Regression cases include gun ownership,
Scorpion/Ghost eligibility, team/weapon gate, enemy range, turning
and cooldown, safe human-Warthog turret pickup, and absence of direct
physics/damage/seat manipulation. These verify rules, not physical
vehicle tag behavior on a phone.

**Physical acceptance still required:** two or more humans and
4–8 bots on Blood Gulch, PC vehicle preset. Turn on experimental
ground driving. Watch a bot Scorpion fire its cannon at enemies,
then leave a human driving a Warthog slowly near friendly bots to
check if a gunner boards. Check the controls with and without CTF.
Afterward export diagnostics for seat/shot traces and map load
issues. A Scorpion's second seat or a nonstandard custom vehicle
may behave differently; capture its real symptom before generalizing.

The multiplayer protocol stays **52**, identical to v30–v32.
The APK package ID/signing identity, updater, campaign saves and
CE map data are unchanged. No installation, map overwrite or
gameplay asset modification is performed by the source patch.
