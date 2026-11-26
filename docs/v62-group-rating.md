# Halo v62 — Shared Group Spartan Rating

Career cards already synchronize automatically by default. This release
adds persistent player-ID exchange over the actual reliable LAN game
connection, plus a separate shared ledger of host-issued match results.
Names are cosmetic; changing names cannot reset a rating.

Eligibility: human-only LAN competitive matches (2–16 players, one
player per device), no bots, campaign, co-op or internet sessions.
The original roster is captured at the beginning; each device must
answer its match-specific challenge within 300 game ticks. Older clients
or disabled profile sharing cannot complete the lock. No ranked result
is invented for an incomplete handshake. Lock happens as early as the
identities arrive, without delaying gameplay.

Once locked, the host observes each original player on every active
tick. A confirmed departure is a permanent forfeit for this match,
including a later rejoin. A reconnect before the host recognizes a
departure is not a confirmed quit. When all opposition has departed,
the surviving side earns a forfeit victory without forcing gameplay
to end. A normal finish uses the native game outcome. Manual host End
Game or abandonment charges the host a forfeit, unless the opposition
already forfeited and the outcome settled. If the roster changes (new
players, bots, team changes), normal ranking is suppressed, but observed
forfeits are retained. There is no invented victory from an interrupted
match.

The host saves a recoverable pending record before accepting a ranked
roster, updates it on departures, and commits its result before sending
it. If the host dies, the launcher recovers the host's forfeit and any
already-observed departures only after confirming the game is stopped.
Unobserved disconnects following simultaneous host failure cannot be
reconstructed; a host that never returns cannot publish its recovery
record. No central service or external account. The rating is a
trusted-friends group ladder, not authenticated or tamper-proof.

Ratings start at 1500, K=32, limits 800–2400. Compare each player with
their actual opposing players' pre-match ratings; average pairwise
expected/actual results and update simultaneously. Teammates are not
opponents. Wins beat losses, equivalent outcomes draw; forfeits lose to
every opponent and cost a full 32 SR (subject to the lower bound).
Abandoned-match neutral participants receive no rating change. Replay
receipts by host epoch and match ID for deterministic convergence.
All previous career/XP/achievement data remains intact; historical
practice SR is separate and not retroactively turned into Group SR.

Named LAN peers automatically exchange canonical receipts with the
existing career-card sync, using bounded digest-negotiated exchange
on TCP 5164. Equal ledgers do not transfer rows. Duplicate receipts
do not count twice; conflicting versions of the same match are
excluded. Data stays on the phones, with a 2048-row capacity and no
silent truncation. Disabled career sharing prevents further exchanges.
Video sharing is unchanged. UI uses Group SR and ranked win/loss/forfeit
counts, rather than confusing the old fixed-reference Practice SR with
opponent-aware ratings.

New optional native messages 103–106; original payloads unchanged,
network version 56 retained. Tests use exact native group logic in a
fake world and ordered transport, and parse its durable output using
the actual Java reader. Physical testing is required: two phones,
human-only match, win/draw, guest departure/rejoin, manual host end,
force-stop/reopen, sync repeated and offline reconnection, bot match,
and unchanged gameplay/recording. Actual Android build and device
behavior must be verified before release.
