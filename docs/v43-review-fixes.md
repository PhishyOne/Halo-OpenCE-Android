# Halo v43: career results, guest highlights and ZIP bounds

Based on v42 `544f3b3e824e7fb3479bb27a2ee579260489a8f4`.
Branch: `halo/review-fixes-v43`. No default-branch merge or upstream PR.

## Changes

- Host-owned kill totals, classifications, best streaks and highlight
  candidates replace the client's transient replay/scoreboard bookkeeping.
- After end-of-match cleanup, before switching clients to postgame, the host
  freezes each real player's final statistics, win, duration and three best
  moments. Clients save only this reliable receipt, once. Rendering the
  postgame scoreboard before receipt arrival cannot commit an incomplete row.
- The host records every actual capturer, including guests. Their selected
  flag-capture highlights use the host's event tick, not packet arrival time.
- ZIP directory records have a 64-entry overall bound and a safe path check.
  They must be empty: unknown-size entries are probed for one output byte,
  then rejected immediately if nonempty. No unbounded closeEntry drain.
  The existing 32-map and 600 MiB file limits remain.
- The web link also exposed a pre-existing missing damage-helper declaration.
  Its shared header now declares the existing void return type, avoiding an
  incompatible implicit-int call in the legacy orbital module.

Network version **53** is required on every peer, including Linux/web hosts.
Messages 101/102 carry a game seed and generation, a full local player datum,
mode, validated statistics and bounded moments. Datagrams, batched messages,
foreign players, wrong generations, inconsistent counters and repeated
receipts are rejected. Neither clients nor a missing receipt can fabricate a
completed result. The host remains trusted for local progression; this is
not a public leaderboard authentication protocol.

The local HC2 / HLM1 formats, journal limits and saved career IDs are retained.
No maps, campaign saves, existing history or controls are rewritten.
Disconnected/crashed matches without a final receipt get no completed award.

## Verification before the final Android build

- Android workflow's source checks: 131 tests and 76 subtests passed before
  the additional transport-hook ordering assertion was added; final totals
  are recorded with the build evidence.
- All 16 standalone Java programs passed (649 individually reported checks
  plus the LAN configuration smoke test). ZIP regression includes known-size
  and unknown-size folder payloads, excess empty folders and unsafe paths.
- Production kill-credit and CTF-award functions run inside the new native
  regression fixture, together with the actual host collector, wire receiver,
  highlight selector and journal writer. Cases cover both packet orders,
  absent final receipts, stale scoreboards, duplicates, old games, wrong
  transport/player, malformed counts, death resets and the guest capture tick.
  Four deliberately broken variants fail the relevant checks.
- 31 selected native/engine tests plus 41 subtests passed as real ILP32
  binaries through qemu-i386; all seven changed C translation units also
  passed the native compiler with the game's actual headers and ABI flags.
- Network format manifest and whitespace checks pass.
- The complete release web build passes with the pinned Emscripten 6.0.10
  SDK. The first Android link caught a private game-mode helper call in the
  new receiver; it now uses the public variant accessor. The corrected
  collector/receiver/journal tests pass as ILP32 binaries, and all seven
  changed C translation units pass with implicit declarations treated as
  errors. The final Android build must pass on the corrected commit.

Two legacy bot-lobby tests remain baseline failures, reproduced on untouched
v42 with the same 32-bit runtime: `lan-sixteen` expects 16 instead of the
configured 8, and `bots-in-any-gametype` mutates a Slayer-only condition that
no longer exists. This change does not alter bot-lobby behavior.

## Phone acceptance

Install v43 as an update. All players in a match need it. Complete a LAN
Slayer match and compare each player's saved kills/deaths with the final host
scoreboard. Repeat with a guest capturing a flag in CTF; confirm the marker
on the guest's profile. Reopen the profile to check that XP was awarded once.
Physical multi-phone acceptance and frame-time measurements remain required.
