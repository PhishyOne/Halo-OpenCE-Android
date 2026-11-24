# Halo v53 — Phone-to-phone leaderboard and provisional Spartan Rating

## Purpose and limits

The user requested **a rating and leaderboard that compare the stats on each
connected Android phone without requiring any permanent server**.

This implements local **PRACTICE SR** and a **peer-to-peer LAN leaderboard**,
not an official, verified competitive ranking.

The trusted historical facts already available in v52 are host-reported HC3
career summaries on each phone. They do *not* contain a shared match ID, a
persistent identity for each opponent, the full verified human/bot lobby roster,
or a signed pair of corresponding results. We must not present a skill rating
computed from these records as authenticated competitive Elo.

Nothing uploads to Forge, Phishtopia, Nomatoka, the internet, or a new database.
No code paths in the native game, networking wire protocol 54, bots or gameplay
are changed for this release.

## Data ownership, storage and privacy

Each Android app install generates and persists its own random **128-bit
pseudonymous ID**, distinct from the display name. Display name changes update
the same ID in the leaderboard rather than creating another player.

The player's **existing local HC1/HC2/HC3 career journal remains the only
source for their stats**, including wins, matches, kills, deaths, assists and
career XP. The ledger is not modified, migrated, copied or shared over LAN.

An optional **8 KiB local cache** (lan-board-v1.txt in the app's own private
external-files folder) holds at most 24 peer cards, each containing:
- pseudonymous player ID and 16-character display name,
- provisional rating and count of eligible results,
- earned XP, wins, matches, kills, deaths, assists,
- last-seen time for stale/offline markers.

Self-reported peer cards are explicitly marked **unverified**. A client may
forge its own score or nickname; nonces and subnet restrictions are not
authentication. The UI does not claim anti-cheat, global standings, or
verified competitive rating. No standalone account or login is required.

## Practice SR

Pure Java deterministic function, starting at **1500** against a **fixed
reference rating**, with larger adjustments for the first ten eligible
results; clamped to 800–2400. This is **not true Elo vs actual opponents**
because their identities/ratings are absent.

Only completed, active, multiplayer, extended HC2/HC3 receipts with
classified **human victim kills, zero bot kills**, and at least 60 seconds
and measurable activity can contribute. Co-op, aborted/DNF, unclassified
and matches with recorded bot kills are excluded. Crucially, *zero recorded
bot kills does not prove the lobby had no bots*. Consequently all such
values are explicitly tagged **PRACTICE SR**. Verified competitive
Spartan Rating remains **UNRATED**.

UNSC Service Rank and XP are a separate unchanged progression.

## Hotspot sharing: all opted-in devices

- Open **Profile → VIEW / SYNC LAN LEADERBOARD**.
- Enable **SHARE MY CAREER WITH NEARBY HALO PHONES**. Default **OFF**.
- With the launcher open, the device serves a small leaderboard card from its
  **private local** cache on **TCP 5164** and responds to nonce-bound
  discovery on **UDP 5165**.
- The launcher closes its local listener before starting Halo. If LAN mode
  is active and the player opted in, the **:game process** serves the same
  card on TCP 5164/UDP 5165 while the game is running. Both hosts and guests
  work. This never changes the actual game port 5150/5151, host update 5162,
  game discovery 5163, or native lobby/game rules.
- Opening the board while sharing enabled triggers one bounded subnet
  discovery scan, then exchanges cards with **all found peer phones** (up to
  16 for a scan). **REFRESH LAN** repeats this explicitly. It is not an
  always-on polling background task. When Android hotspots block
  broadcasts, enter a peer phone's **private IP** in the optional field.
- Each contacted peer returns its own currently computed stats and cached
  standings, up to 24 cards, letting any participant gather a shared view
  even while others are in gameplay. Newest time-stamped peer data wins;
  the local phone's own card always wins its identity.
- Cards include K/D and win-rate calculations only using the transmitted
  aggregate numbers. **Previously synchronized players remain listed
  after disconnecting**, even weeks or months later, while within the
  24-player local cache capacity. Each row displays the **absolute date and
  local time of Last synced** (when their career summary was last received).
  This date is NOT necessarily when they last played a match. A cached entry
  also does NOT prove the player is online or offline now. Phones that have
  never shared their statistics cannot appear automatically.
- TCP and UDP bind only eligible private LAN/hotspot interfaces, with
  strict private-subnet checks, fixed-size lines, nonce-bound discovery,
  finite I/O timeouts and no arbitrary file requests or Internet DNS.

There is **no permanent hosted server**. One phone only temporarily answers
another phone's direct request. A separate central host is not required.

## Verification / remaining testing

- Pure Java career-rating + protocol/store/discovery fixture checks
  (LanBoardCoreTest) cover eligible vs bot/unclassified/unfinished matches,
  source-data preservation, nonce rejection, bounded parsing, duplicate ID,
  rename continuity, missing partial records, last-synced ordering,
  retention of offline players beyond 130 days, rejection of future-dated
  gossip, cache atomicity and size caps.
- Source contract tests check all Android lifecycle hooks, explicit sharing
  opt-in, peer discovery, subnet restrictions, host/guest game support,
  localhost-only file storage and no cloud APIs.
- Android API35 Java-source compilation (with existing local SDL test stub).
- Hosted Android-only signed exact-head build/versionCode 53, prior app signing
  identity and unchanged bundled icon/game/font verification.
- **Real two-phone acceptance is required** before declaring cross-phone
  synchronization proved: install v53 on at least two players' phones;
  connect to the same hotspot, enable sharing separately on both.
  Open leaderboard on one phone while the other stays in launcher, then repeat
  while the other is *in the game*. Verify both IDs, names, exact XP, K/D,
  wins, practice SR and stale/refresh behavior. Repeat after force-stop/reopen,
  a profile rename, and a new completed match. Verify no crashes or LAN lag.
  If discovery fails, use the optional peer IP; never pretend it works
  without empirical testing.

## Follow-up for certified rated matches

A real competitive Spartan Rating (Elo/Glicko-like) needs:
1. Host-issued common 128-bit match ID persisted on all participants;
2. stable player IDs in the game handshake, mapped to the complete match
   roster and verified bot-free settings;
3. host-signed and reconciled terminal match results (human roster, teams,
   winners, forfeits, mode), deduplicated across phones;
4. a transparent tie/quit/disconnect policy, host migration/reconnection
   tolerance, and an audit trail to reject self-reported manipulation;
5. peer sync of compact signed match receipts, **not** a central server.

The above can later use the same direct phone-to-phone LAN transport.
Current v53 intentionally does **not** award verified competitive rating.

Branch `halo/lan-ratings-v53`, based on v52; Forge worktree and temporary
build artifacts on `/mnt/nomatoka-terrain-scratch` secondary volume.
Keep prior v52 signed APK in Downloads as rollback; preserve all installed
game files and saves.
