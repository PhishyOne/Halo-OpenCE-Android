# v63 — Sticks & Stones

A built-in OpenCE multiplayer gametype, appended to the default list
without renumbering the existing presets. Select OpenCE → Sticks & Stones
in the native game type chooser. Free-for-all Slayer by default; normal
score limits and respawns. Uses stock infinite-grenade flag, normal weapon
category for both frag and plasma, empty custom loadout, no map weapons,
and no vehicles on either team. Unarmed melee already exists. Directly
placed guns on unusual custom maps cannot be picked up in these rules.
Bots keep existing grenade/melee combat decisions, skip gun-seeking and
approach melee range. Existing configurable killstreaks/powerups are
otherwise unchanged. No new network fields or game engine ID.

VIP is a separate next feature, not implemented in this patch. Proposed
team-based round state: host chooses living escort-team VIP, grants stock
overshield, and uses a verified opposite-team CTF anchor as destination.
User explicitly requires the VIP to be unarmed: strip their weapons and
grenades, reject weapon/equipment pickups and prevent weapon use for the
entire role (including after respawn or a role change). Proposed default:
also suppress offensive melee and weaponized vehicle positions; allow
movement and passenger transport, so the escort team actually has to
protect the VIP. Do not disable the ordinary players' weapons. Give the
VIP a stock overshield at round start, not continuous invulnerability.
Escort scores on arrival; defenders score on VIP death, timeout or
disconnect. Swap escort side after each round. Reset/respawn fairly between rounds,
host-authoritative replicated VIP identity/phase/score and navpoints,
no competing Slayer kill victory. Define a complete pair of rounds and
test missing/invalid map anchors, bots, death, departure, duplicate
round awards and late join before enabling rating. Keep VIP unrated
until its match result and departure semantics are validated.

Acceptance for Sticks & Stones: on two updated phones select the preset,
confirm both types replenish, no firearm pickup or vehicles, unarmed
melee works, score/respawn and LAN results agree, then test offline bots
and a custom map. Build tests are not physical gameplay acceptance.
