# v73 — Unified launcher lobby and rule bridge

Navigation: Leaderboard | Lobby | Offline Match | Tools.
Lobby selection/creation leads to one shared pregame screen. Real private LAN
membership uses port 5167, with explicit create/join, host-owned state,
guest-owned appearance/readiness and team-change requests. Guest rows and
planned bots are grouped by team. Classic Halo retains the original Host/Join
entry points and LAN settings. Leaving the launcher foreground closes this
first-phase pregame; the native multiplayer session bridge is not enabled.

Host readiness is advisory. A Start review selects a specific entry, even for
shuffle, computes the actual roster and planned bot allocation, names the
unready guests, and offers Start Anyway. Authorization keeps all members.
Changes in the match plan or relevant player identity/team require a new
review; readiness-only changes do not invalidate a confirmed override.
Hard invalid-plan/participant/capacity checks cannot be overridden. An
authorized immutable ticket is not an engine acknowledgement. Native LAN
Start stays disabled until host/guest launch, profile mapping and roster
handoff are complete.

Workshop stores reusable custom-mode revisions and snapshot playlist entries,
supports reordering, shuffle/loop, stock and custom multiplayer map selection,
and balanced/manual/versus/extra-bot policies. Local BMP previews only.
Offline Match now offers all 35 built-in presets plus saved Workshop modes
on installed stock maps, retaining Classic menus. The direct offline
launcher request passes bounded canonical integer overrides; the native
bridge builds actual game variants/options and validates 71 rule keys.
Killstreak radio overrides use the existing separate configuration.
Explicit loadouts and vehicle counts select the required custom policy;
conflicting options, unknown keys, duplicates and invalid ranges are rejected
without modifying the original variant. The direct-launch pregame waits
for the native roster and countdown readiness, checks selected map/variant,
and falls back on failure. No native gameplay wire-layout changes.

Known limitations: new direct offline launch, custom rules and real LAN
pregame need device acceptance. Native LAN Start/return-to-lobby,
actual LAN team/bot assignment, native name/color sync, playlist gameplay
rotation and stock preview extraction remain incomplete. Gravity, damage
multipliers, extended Gun Game stages, voting, rating balance, Flood and BR
are future work. Do not claim these are implemented.

Validation: 59 local CI source commands, all JDK suites, touch/gyro checks,
full Android API35 Java compile, C rule bridge harness using engine field
declarations, 71-key Java/native schema agreement, 24 readiness checks,
21 loopback lobby checks and 18978 Workshop checks passed.
