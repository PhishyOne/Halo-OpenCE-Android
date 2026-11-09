# Experimental CTF and King of the Hill bots

This is a source prototype, **not yet an Android-play-qualified build**.
It extends experimental LAN bots and postgame handling, and changes no
network wire messages, native CTF/King scoring, game assets, or signing keys.

## Opt-in

Use the host's LAN launcher checkbox "Experimental: bots play CTF and King
of the Hill", with LAN-only mode and 1–16 host bots. It saves the
bots.objective_modes configuration key, which defaults false. Slayer
continues using the existing behavior. The network clients do not run bot AI.
Offline split-screen bots may also use the setting with bots.count.
Internet game hosts never get these LAN bots.

## Behaviors

- King and Team King: each bot goes to a live hill center, distributes
  across multiple hills, follows a moved hill, and fights as it advances.
  The game's normal hill membership and score code decides control.
- CTF: about one in three bots defends/intercepts at its own flag;
  the rest seek the enemy flag, including while another player carries it.
  A bot carrying an enemy flag follows the engine's capture marker home.
  Normal player actions handle flag pickup. A missing capture marker
  falls back to seeking the bot's own flag rather than the enemy stand.
- All destinations use the existing walkable-surface pathfinder. The
  engine continues controlling spawn, death, pickup, scoring and teams.

## Vehicle glitch investigation

A bot once failed to fire at a human vehicle occupant and later recovered.
The existing visibility code already allows hits on the target's parent
vehicle. The cause is not established. Rather than guessing at aim, this
branch adds diagnostic counters: vehicle enemies checked, in range/view,
line-of-sight clear, and line-of-sight blocked. They appear in the host's
minute bots debug log. Enable debug.bot_trace for sampled target and trigger
details if it happens again.

## Qualification before use

- Source tests: Python C policy harness and plain-JDK LanConfig smoke test.
- Missing: Android game compile, packaged signed APK, actual bot movement
  and capture scoring on devices, and multi-round network stability.
- Start with 4 bots and four humans on Blood Gulch, then 8, later up to 16.
  Team King: enter hill, contest, move to next hill, score on both phones.
  CTF: capture and return enemy flag, protect own flag, escort/defend,
  verify scoring, and repeat for three full rounds.
- Test postgame guest WAIT FOR HOST and host CONTINUE; verify next lobby
  without disconnection.
- Keep previously installed APK and backup maps/saves until a fully tested
  permanently signed update is available. Leave this PR draft meanwhile.
