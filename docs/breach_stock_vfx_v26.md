# Halo v26: reuse stock grenade effects for Breach Pulse

**Status:** Source-qualified candidate based on v25
`6d197beb12173f2ca79486f097d4fa3246682b09`.
Native Android build and real-map, two-phone visual acceptance remain separate gates.

## Intent and asset source

The v25 indoor ten-kill Breach Pulse already credits host-controlled,
enemy-only damage and has British radio plus HUD text. It previously had
no actual world animation.

v26 uses Halo's existing `effect_new_unattached_from_markers()` renderer
to place a **stock plasma-grenade detonation effect** at the exact
host-approved Breach Pulse center. Rather than embedding an effect tag,
bitmap, particle, animation or other copyrighted map assets into the
Android APK, the engine looks up the active map's
`scenario_get_game_globals()->grenades` table, resolves the Covenant
plasma projectile, and takes that projectile's normal
`projectile.effect` reference. If absent, try the human fragmentation
projectile's effect. If both are absent or invalid, skip the world visual
and keep the existing authorized HUD/audio/damage. No guessed tag path
or external download is required.

This is a **stock explosion**, not a custom Battlefield 2142 particle
sequence. The colors, lifetime and scale are determined by the map's
existing effect tag. Custom Edition maps may replace or omit those tags;
a missing visual must never alter gameplay.

## Authority and LAN sequencing

The host already validates the ten-kill entitlement, life, team, machine,
roof and geometry, then accepts one Breach Pulse. The public `kind 5`
incoming warning is still separate from the actual detonation.

When the timer reaches the true impact, **after rechecking that the
awarding life is still valid**, the host emits a new `kind 6` visual
event over the existing reliable host-to-clients message type 100
(36-byte payload unchanged). It also queues that event locally.
Each client requires the preceding authenticated warning for the same
serial and matching point, accepts the visual event once, and invokes
the stock effect renderer in its own game update, independent of HUD
visibility. A warning alone never creates an explosion: if the owner
dies and the pulse is canceled, no detonation event is sent.

The visual function never inflicts damage, chooses victims, changes
teams, or awards kills. The host's existing area/radius/LOS validator
still performs all damage, with normal replication. The visual is one
local rendering side effect per validated impact.

**Network protocol 48 → 49** since message kind 6 is newly recognized.
v25 and v26 cannot join each other's LAN sessions. All players must
update; the existing v23+ host-to-joiner APK update feature remains.

## Verification and limits

Asset-free source tests:

```sh
python3 -m unittest -v tools.test_breach_stock_vfx
CC=gcc python3 -m unittest -v tools.test_killstreak_orbital
```

The stock-effect C fixture compiles the actual selection function and
checks plasma preference, frag fallback, exact coordinates, and
safe missing-tag handling. The larger C fake-world harness checks host
and client event delivery, serial mismatch/replay, single visual spawn,
no effect on canceled impact, all existing orbital/recon rewards, and
negative security mutations. The Android-only CI workflow includes
both tests before native compilation.

Before calling it playable, visually check the stock particle effect
on a native and a custom indoor map, in first and third person, with
all LAN peers connected. Check effect timing against damage, owner
death after warning, two simultaneous Breach awards, and performance
with many bots. Check a map with intentionally missing projectile
references: it should produce no visual, but gameplay/audio remain.
No new weapon projectiles are spawned. No guaranteed rendered
animation or two-phone confirmation is claimed by the fake-world tests.
