# Halo v37 — Local Spartan Service Record

**Branch:** `halo/career-profiles-v37`, based on signed v36
`a9b2594`. The source and release review run on the secondary volume.

## Scope

Add an offline, append-only career journal for each Android install,
viewable from the v36 launcher through **SERVICE RECORD** or
**Tools → My Spartan Profile & Achievements**. Existing controls, modes,
multiplayer packets, bots, maps and save profiles are unchanged.

- Native engine writes one result when the multiplayer postgame screen
  first appears, from the local controller only (not the host's bots or
  friends). Host and client both write their own local results.
- Every record has a random 128-bit match ID, timestamp, game type, sanitized
  map token, kills/deaths/assists, match win, best killing streak, flags,
  objective counters, suicides, and friendly fire.
- Kill events are classified by *actual bot machine index*, never fake names.
  If a guest has a scoreboard total but insufficient kill-event details,
  the total is retained and split fields become unknown; the UI does not
  guess that they were human kills.
- A user-editable local display name is stored in Android preferences.
  It does not pretend to be an authenticated multiplayer identity.
- The Java reader validates every line, ignores an incomplete crash tail,
  deduplicates match IDs, enforces a 2 MiB history bound, and computes
  career totals from source records. No state is rewritten on view.
- Experience progression awards XP for finished games, wins, assists,
  objectives and combat. Bot-kill XP is capped to reduce farming.
- Twelve career ranks and nine achievements (First Blood, War Veteran,
  Untouchable, Human Hunter, Bot Buster, Flag Hero, Perfect Game, Team
  Player, Champion). The profile shows the latest five completed matches.

## Scope deliberately *not* included

No global leaderboard, automatic online account, social graph, server
cost, competitive MMR/Elo rating, live achievement announcements,
headshot counts, or server-certified match signatures. Local profile
data is not tamper-proof. A host's authoritative multiplayer scoreboard
does not automatically certify the records stored on other phones.

The recorder only runs on Android and only for completed multiplayer
game engines (CTF, Slayer, Oddball, King, Race). Campaign co-op, a match
that terminates before postgame, and an early quit are not counted.
If a guest missed human-versus-bot kill events, unclassified kills are
reported separately. Wins have not been field-qualified with a real
multi-phone session.

## Persistence and storage

The native game appends small ASCII records to:

`Android/data/com.halo.decomp.experiment/files/career-matches-v1.log`

(Android's scoped-storage path on the test package). This folder belongs
to the app, not the APK. Updating in place retains it; uninstalling might
erase it. Users must install **Update**, not Uninstall. No old map,
profile, campaign save or game configuration is touched.

An interrupted file write is treated as an invalid partial line. The
next writer inserts a separator so later valid records aren't lost.
The reader rejects oversized history with a visible warning rather
than silently deleting records. Random IDs prevent duplicate imports,
but do not provide authenticity.

## Validation

- `python3 tools/test_career_records_v37.py`: source integration and
  Android-only scope.
- `python3 tools/test_career_native_v37.py`: the production C writer
  extracted into a tiny fake Halo world and executed; checks exact
  serialization, no duplicate postgame, bot/human split, quit rejection.
- `javac ...CareerRecords.java ...CareerRecordsTest.java` and
  `java ...CareerRecordsTest`: 30 checks for malformed records,
  duplicates, incomplete tails, XP caps, achievements, and rank thresholds.
- Android Java source compilation against the Android 35 SDK.
- One exact-head Android-only Actions run with all prior regression tests,
  signing, native ILP32 build and release packaging.

## Physical acceptance

1. Install signed v37 over v36. Check that existing maps, saved games,
   launcher settings and network game still function.
2. Open SERVICE RECORD: starting Rank 1 / zero matches is correct.
   Change the Spartan name.
3. Finish a solo offline Slayer bot match through the postgame screen;
   reopen the launcher and confirm Games +1, kills, deaths, bot kills and
   XP advance exactly once.
4. Finish a LAN match with at least one human and bot opponent; check
   human/bot separation on the local phone. The second phone keeps its
   own local profile.
5. Check CTF capture achievements in a finished CTF match.
6. Return to the postgame screen or relaunch without another match;
   no duplicated progress.
7. Abort a match before postgame: no completed-match award.
8. If the postgame appears but stats remain zero, capture the game's
   debug output for `career:` and inspect app storage permissions.
   Do not uninstall to troubleshoot; preserve the journal and logs.

Do not announce verified competitive ranking or cross-device career
leaderboards until an authenticated host-result protocol is designed.
