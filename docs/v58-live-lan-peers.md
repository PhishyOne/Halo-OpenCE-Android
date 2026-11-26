# Halo v58 — Live LAN peers

The launcher masthead shows the number and names of currently detected Halo
peers, independent of the saved leaderboard. Tap for the full list, private
IP, launcher/game-open state, manual scan and leaderboard shortcut. Green
text means at least one current named TCP responder; unnamed discovery-only
peers may run an earlier version or have an unavailable TCP listener.

Existing local leaderboard sharing opt-in is required to announce identity.
With sharing off the user can still discover opted-in peers, but does not
respond with their own name/career. Enable the existing opt-in on both
devices to exchange stats. v58 allows opted-in game processes to respond
in any mode; "Game open" is process state, not proof of an active match.

UDP 5165 discovery remains backwards compatible; a small nonce-bound HBP1/
HPA1 identity-only request on existing TCP 5164 confirms ready named peers.
No gameplay wire version changes, cloud traffic, broad subnet sweeps, full
career data in discovery, automatic stat exchange or persistent presence
cache. Opening the leaderboard shows cached stats; REFRESH LAN explicitly
syncs and uses the fresh peer addresses as fallbacks. Last sync dates remain
separate. Only the visible launcher polls, one bounded scan at a time,
starting another scan six seconds after completion. A snapshot expires at
15 seconds. A missed scan is not proof of disconnection.

The server checks current private LAN interfaces every three seconds, so
joining/leaving a hotspot after opening the launcher can rebind the board.
The UDP responder holds a Wi-Fi multicast lock only while sharing is active.
Known peer IPs and private Wi-Fi gateway are fallbacks if broadcast fails.
Restricted Android hotspots can still block discovery; older clients are
anonymous until they update. No enumeration of unrelated hotspot devices.

Device acceptance: install v58 on two phones, enable share, join hotspot
while launcher is already open, verify names and game-open status on every
tab, close/reopen and disconnect/reconnect one phone, verify old rows retain
last sync date and no stat sync until pressing REFRESH LAN. Check v57 peers
appear as unnamed discovery-only and remain compatible with manual sync.
Check off-opt-in, repeated rapid resume, game handoff, and manual IP fallback.
