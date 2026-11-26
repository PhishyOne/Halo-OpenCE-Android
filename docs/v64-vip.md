# Halo v64 — VIP Escort

OpenCE → VIP Escort, native two-team mode. The existing Slayer engine
supplies player spawning, normal health and death/respawn behavior; the
VIP engine owns all round wins, scoring, player restrictions and markers.
Round duration: three minutes. Each team gets one attempt; the host
alternates which team starts across matches (game number parity).
A draw is possible if both teams win one round.

Requires separate, valid CTF base markers and CTF-compatible starting
locations for both teams. The escort destination is the opposite team's
flag location (within 2 horizontal map units and 1.5 vertical units).
Missing/overlapping anchors or a team that cannot spawn abort the game
without a fabricated victory. A 30-second preparation deadline avoids
waiting forever. After both teams have living units for three seconds,
select a human VIP if available, otherwise a bot. On the next round
perform a no-statistics reset of living players and delete old projectiles,
wait for fresh spawns, then switch VIP to the other team.

The VIP receives fully charged stock 3x shield vitality once; normal
damage/depletion/decay thereafter. No weapon, grenade, equipment or
powerup acquisition, melee, firing or weapon use. Host disarms the VIP
each active tick and filters offensive damage; player/unit controls are
also filtered. Other players retain normal weapons and melee. VIP may
only occupy a passenger seat, never a driver/gunner seat. The default
preset disables vehicles for the initial test; customized vehicle sets
are not yet validated for balanced round resets. Friendly fire, automatic
team changes, Hardcore and killstreak rewards are disabled in VIP.
Bots route to extraction as VIP and pursue the VIP as escort/defenders
with basic distributed offsets and ordinary combat; advanced formations
and vehicle escort remain unvalidated.

VIP death, disconnect or replacing their unit loses the round; reaching
the extraction zone wins it. Death takes precedence if it occurs on the
same tick. If defenders all leave, award the escort a round; if a second
team cannot form, abort instead of inventing a complete match. Duplicate
round events cannot award extra points. A host manually ending the
match before both rounds finish does not fabricate a winner.

HUD uses native role/timer/score text and existing objective markers.
Only host decides, clients apply validated fixed 44-byte state with game
number and revision. Network version 57 is intentionally incompatible
with older versions, including regular matches: everybody in the LAN
group needs v64. APK updater/leaderboard protocols remain unchanged.
VIP excluded from Group SR pending real device acceptance; existing
career history uses the underlying Slayer engine ID. XP/career data
retained. No server writes or new game map assets.

Acceptance: two v64 phones, both teams, Blood Gulch first, then a map
with no valid CTF locations; extraction, death, timeout, guest VIP quit,
both rounds and draw, no weapons/grenades/melee from VIP, overshield
damage/decay, old life reset with no extra career deaths, UI and navpoints,
bot VIP pathfinding, ordinary Slayer/CTF/Sticks & Stones and ranked
matches unchanged. Physical acceptance is required.
