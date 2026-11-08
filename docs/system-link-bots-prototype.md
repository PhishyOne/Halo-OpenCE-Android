# System Link bots: four verified, expanding to sixteen

Four LAN bots have been tested successfully on Android by the user. This isolated
branch expands the host-only experiment to sixteen LAN bots and adds difficulty
selection to the Android launcher. The new counts have not been played yet.

## Prototype switches

```toml
[network]
online = false

[bots]
system_link_count = 1
skill = "normal"
teams = "against"
```

`bots.system_link_count = 0` by default. Values are clamped to 0..16.
Offline split-screen uses `bots.count` (0..127); LAN bots are capped at 16 in the next test build; the two settings
are independent. System Link bots are only eligible in a LAN-only Slayer
lobby, never internet games or campaign co-op.

This test uses **network protocol 45**. Offline local matches reserve machine
IDs 1..127, but LAN-only matches reserve 112..127 for up to sixteen bots,
leaving slots 1..111 available for real joining machines. All players must
run this same APK; prior versions 41..44 cannot join.

## v1 Android test launcher

The Android-only integration adds the proven offline-bots launcher and an
*LAN Slayer bots* selector (0–16) and a *LAN bot difficulty* selector under
**Host game**. All clients run the same experimental build, but choose
**Join game** and enter the Halo host's LAN IPv4. The host can share that
address using the launcher. Only the host can enable bot AI, by selecting
LAN-only mode and one or more bots; joining or switching to internet mode
sets `bots.system_link_count = 0` in the local TOML. Offline bots use
`bots.count` and are entirely separate.

One-button Save persists LAN settings, host bot count and selected host difficulty
in one atomic configuration update without dropping offline bot count, team,
display, audio or other settings. A new test covers client-mode clearing, invalid
count and duplicate TOML sections/keys. Do not install casually:
the debug signing key is not stable and an uninstall erases game maps.

**One-bot test protocol:** host starts System Link *Slayer* on Blood Gulch,
with 1 bot on the host only. Both phones should see bot name in lobby and
scoreboard. One player watches the bot move/shoot, the other shoots it.
Check bot dies/respawns and all kills are scored identically. Save
`debug.txt` on BOTH phones before uninstalling. If successful, repeat
with two, then four bots; test one joining player dying and respawning.
A bot appearing in the host-only scoreboard is not sufficient to claim
networked bot functionality. Campaign co-op and Covenant/Flood waves are
not implemented by this prototype.

## Preliminary source behavior

1. The host adds synthetic bot players to its Slayer lobby, *not* synthetic
   network client connections. The host distributes the game roster to
   real clients. A high-numbered slot occupied by a real machine is never
   overwritten by a bot.
2. The server accepts new real connections in lower-numbered slots while
   this prototype is enabled, leaving the synthetic bot range reserved.
3. Each game tick, only the hosting machine computes `bots_bot_update`.
   A client gets remote player actions and object/unit state, but does not
   run this AI.
4. The exact bot action the host simulated is copied into the server's
   tick history so `distributed_host_plan_players` can relay it to
   clients using its existing compact action message.

**Verified so far:** earlier four-bot candidates passed independent source
and native-build tests; the user reports that four bots worked in physical
Android LAN gameplay. The sixteen-bot limit, multiple difficulty settings,
late joins and prolonged performance have not been physically tested.

## Gate checklist

- Compile and run `tools/harness/tests/test_bots_lobby.py` on an isolated
  runner: unchanged offline behavior, LAN one/four, opt-in required,
  internet excluded, no high-slot collisions.
- Add an asset-free test for the live update-queue action bridge and an
  independent source review; reject stale/update-mismatched actions.
- Exact-source Android APK build and verification on isolated runner,
  no Forge privileged native builds.
- Test a Linux host+client with legal local maps first. Measure actual bot
  movement, firing, unit creation, deaths, roster and reconnect.
- Android LAN with 2/3 actual phones for 0/1/2/4 bots, logs from each;
  CPU and frame-time measurements. Make failure reversible.
- After source and playtest qualification, upstream PR with a small scope
  and the original bot contributor's CC0 attribution. Firefight remains
  a separate feature.

Do not ship the experimental APK to everyday players yet. The proven
two-phone co-op build and v2 multi-join candidate are separate branches.
