# Halo Android v28: Up to 64 bots in local System Link multiplayer

**Status:** Candidate based on verified v27 at `29dfe47`. Exact-source
Android build, signature check, two-phone gameplay and frame-time tests are
separate gates. v27 stays on the phone until verification succeeds.

## Goal and how to select bots

The Android launcher **LAN / SYSTEM LINK → HOST → BOT COUNT** now offers
compact presets: `None, 2, 4, 6, 8, 12, 16, 24, 32, 48, 64`. Instead of
a 65-row spinner, legacy non-preset values such as 3 or 7 are temporarily
included so entering the launcher cannot silently change the count.

During a LAN or offline multiplayer **pregame lobby**, the host's
large Android **− / BOTS / +** touch strip can move through the
same progression, including `16 → 24 → 32 → 48 → 64`, without
exiting Halo or restarting the app. The lobby's old XML bot buttons
also share the same native count policy. Once the game starts, controls
hide and cannot change active players. Guests cannot adjust the host's
count. Offline 0–127 bots continue unchanged.

## Capacity model

The engine already has **128 total network player and machine slots**.
For LAN-only games v28 reserves machine indices **64–127** for up to
64 synthetic players; indices **0–63** remain available for real clients.
Four humans plus 64 bots require **68 player slots**, within 128.

A bot is never allowed to impersonate a real LAN machine. The server
checks that both client and game machine records at a prospective
virtual index are empty before allocating a bot. It stops when the
game's configurable maximum player count is reached. The UI default
is designed for up to 128 total players, but a separately chosen lower
server-player limit can still prevent reaching 64 bots.

The actual AI, aim, vehicles, pathing and damage are simulated by the
**host** and replicated to peers; clients do not run autonomous bot AI.
Host-only LAN remains separate from internet and campaign co-op.
LAN bot count uses the distinct `bots.system_link_count` TOML setting;
the offline `bots.count`, campaign saves, maps and settings are untouched.

At higher LAN counts, a single server idle tick adds **at most eight
new bot players**, avoiding one burst of 64 roster allocations. The
existing `<=16` LAN and offline 127-bot paths are unchanged; returning
to a lower count can remove extra bots promptly while in pregame.
The existing AI limits path searches to three per simulation tick,
although crowd collisions, LOS checks, vehicle traffic and corpses can
still increase CPU load. No performance estimate or guaranteed smooth
FPS is claimed by the source tests.

## Wire and compatibility

The shift in virtual machine range changes the interpretation of IDs
from 64 through 111, so gameplay **network protocol 49 → 50**.
All phones in the LAN game need v28; earlier versions cannot join.
The existing LAN host APK updater (v23+) should offer v28 to peers
over the hotspot before they enter the match. Normal Android in-place
Update with the permanent signing certificate preserves player
profiles and campaign checkpoint files; never uninstall to update.

## Qualification

```sh
PATH=/home/phishyone/.local/bin:$PATH CC=cc python3 -m unittest -q \
  tools.test_lan_64_bot_capacity \
  tools.test_lobby_bot_count \
  tools.test_android_launcher_layout
javac -d /tmp/halo-v28-jdk \
  port/android/app/src/main/java/com/halo/decomp/LanConfig.java \
  tools/lan/LanConfigSmokeTest.java
java -cp /tmp/halo-v28-jdk com.halo.decomp.LanConfigSmokeTest
```

The Android-only CI workflow runs these along with all existing
native-game and Android tests before building the signed APK.

**Physical acceptance:**

1. With a host and one guest, start with 8–16 bots, then select 24, 32,
   48 and finally 64, confirming the roster fills before pressing Start
   and that the guests receive each bot once.
2. Test a wide map and a small map, including Slayer, CTF and King.
   Verify team balance, kills, spawn positions, bot vehicle seats and
   human visibility after death/respawn.
3. Watch FPS, frame pacing, packet loss and connection recovery at 32,
   48 and 64, including when bodies accumulate. Reduce the count in
   the pregame lobby if stability degrades.
4. Test changing down to 16 and back to 64 and confirm human
   connections and profile/campaign saves remain intact.
5. Test a host that limits total slots below 68 and confirm it stops
   creating bots rather than overflowing the player array.

If physical results are poor, use the presets at 16–32 for ordinary
matches; large counts are an opt-in experiment, not the new default.
