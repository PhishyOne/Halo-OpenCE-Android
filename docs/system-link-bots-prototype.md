# Experimental System Link bot prototype (not user-ready)

This isolated branch explores the **first** host-authoritative player-style
bot in a LAN System Link Slayer match. It is deliberately NOT merged into
the working Android LAN APK or the three-phone launcher.

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
Offline split-screen uses `bots.count` (0..127); LAN bots remain capped at 16; the two settings
are independent. System Link bots are only eligible in a LAN-only Slayer
lobby, never internet games or campaign co-op.

The next LAN test uses network protocol 45. Offline local matches reserve machine IDs 1..127 for up to 127 bots; LAN matches reserve machine IDs 112..127 for up to 16 bots, leaving 1..111 for real clients. LAN difficulty is selectable by the host. All participating LAN devices must use the same APK; versions 41..44 cannot join. Do not mix
APK builds from this branch with the stable two-phone or three-phone builds.

## v1 Android test launcher

The Android-only integration adds the proven offline-bots launcher and an
*Experimental LAN Slayer bots* selector (Off / 1..16) under
**Host game**. All clients run the same experimental build, but choose
**Join game** and enter the Halo host's LAN IPv4. The host can share that
address using the launcher. Only the host can enable bot AI, by selecting
LAN-only mode and one or more bots; joining or switching to internet mode
sets `bots.system_link_count = 0` in the local TOML. Offline bots use
`bots.count` and are entirely separate.

One-button Save persists LAN settings, the bot count, and host-selected
Recruit/Easy/Normal/Heroic/Veteran/Legendary difficulty in one atomic
configuration update without dropping offline count, team, display,
audio or other settings. Offline Bot Match still uses its own per-launch
skill override. A View / Copy Game Debug Log button makes Android map
failures (including reported Sidewinder blue-screen rendering) easier to
investigate without ADB or filesystem access. A new test covers client-mode clearing, invalid
count and duplicate TOML sections/keys. Do not install casually:
the debug signing key is not stable and an uninstall erases game maps.

**One-bot test protocol:** host starts System Link *Slayer* on Blood Gulch,
with 1 bot on the host only. Both phones should see bot name in lobby and
scoreboard. One player watches the bot move/shoot, the other shoots it.
Check bot dies/respawns and all kills are scored identically. Save
`debug.txt` on BOTH phones before uninstalling. Four LAN bots reportedly
worked successfully in a physical two-phone test (user report, no logs).
For the new limit, try 6, 8, 12, then 16 bots; test one joining player
dying and respawning, as well as each available difficulty.
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

**Evidence boundary:** The original four-bot LAN path passed isolated
source tests and native Android builds; the user reports it worked in a
physical LAN match. The new 6..16 counts, selected LAN difficulty,
late joins and deeper replication failures are NOT physically verified.
This source is still experimental and not upstream-qualified.

## Gate checklist

- Compile and run `tools/harness/tests/test_bots_lobby.py` on an isolated
  runner: unchanged offline behavior, LAN one/sixteen, opt-in required,
  internet excluded, no high-slot collisions.
- Add an asset-free test for the live update-queue action bridge and an
  independent source review; reject stale/update-mismatched actions.
- Exact-source Android APK build and verification on isolated runner,
  no Forge privileged native builds.
- Test a Linux host+client with legal local maps first. Measure actual bot
  movement, firing, unit creation, deaths, roster and reconnect.
- Android LAN with 2/3 actual phones for 0/4/6/8/12/16 bots, logs from each;
  CPU and frame-time measurements. Make failure reversible.
- After source and playtest qualification, upstream PR with a small scope
  and the original bot contributor's CC0 attribution. Firefight remains
  a separate feature.

Do not ship the experimental APK to everyday players yet. The proven
two-phone co-op build and v2 multi-join candidate are separate branches.
