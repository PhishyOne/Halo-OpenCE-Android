# Halo v61 — Flat launcher controls

Five top-level tabs: Leaderboard, Offline Match, Host, Join, Tools.
Campaign is a visible radio choice under Offline Match, alongside Bot
Match. The last offline choice persists; an older standard/campaign
selection is respected on migration. Only bot-specific controls are
hidden when Campaign is selected; it still uses the existing standard
native launch/config path and preserves checkpoints.

Remove all six launcher disclosure toggles: Network Options, Killstreak
Rewards, Map Repair, Share Maps & Backups, Custom Maps, Campaign Saves.
The existing themed dropdowns and checkboxes are directly exposed in
their relevant page sections. Tools are vertically scrollable, with no
extra expandable subsections. Killstreak controls appear directly for
offline bots and eligible LAN hosts only. Match history disclosure cards
are intentionally retained; they are not launcher settings.

Both recording preferences are in Join's Match Recording section, not
the fixed Play footer. The keep-full checkbox remains visible but is
disabled if recording is disabled. Both use the same global preferences
and affect hosting, joining and offline multiplayer. Existing Android
consent and match-aware capture/cleanup rules are unchanged. Campaign
and co-op are excluded from capture. The fixed footer retains Play,
feedback and the conditional Android app-settings action.

No changes to gameplay/network protocol, stats, rating or video files.
Destructive tools retain confirmation, repair busy and game-stopped
guards and system picker flows.

Acceptance: test all five tabs at phone landscape size, offline bot
choice -> campaign -> Host -> Join -> Offline, campaign checkpoints,
remembered choice after restart, invite/discard, direct reward controls,
Tools visibility and confirmations, recording setting changes in Join
then use Host and offline multiplayer. Physical UI verification pending.
