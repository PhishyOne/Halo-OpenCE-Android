# Host-authoritative bots and Firefight for OpenCE

Status: **architecture spike, NOT a working gameplay feature**.
Date: 2026-11-07. Scope: portable OpenCE source, not Android-specific glue.

## Goal

Enable three or more players on the same System Link LAN to play with
server-controlled NPC opponents, using one host's authoritative simulation,
without multiplying expensive AI across clients. Contribute portable,
source-reviewed changes upstream to OpenCommunityEdition/OpenCE *after*
repeatable real-device tests.

The already proven Android LAN discovery fallback (fork PR #1) and
simplified three-phone launcher (fork PR #2) remain separate. Do not change
them here. This worktree begins at fork main 899219c, so it can be
integrated upstream independently.

## Existing reusable work

- OpenCE PR #367, merged 2026-10-19, adds offline Slayer bots from the
  Halo CE PS Vita derivative. The PR credits original bot code to
  BirchWoodGod (contributed under CC0); OpenCE itself is AGPL-3.0-or-later.
  Follow the repository's AUTHORS/LICENSE rules. No game assets included.
- `port/linux/game/bots.c`: complete player-style combat, skill levels,
  grenade use, target selection, and pathfinding with a three-search-per-tick
  budget. It currently exits from `bots_update_actions` unless the game
  is split-screen local.
- `source/networking/network_server_manager.c`:
  `network_game_server_port_bots` adds bot players to the **offline**
  lobby and updates teams. Each synthetic bot has a machine slot in
  `BOTS_FIRST_MACHINE..BOTS_FIRST_MACHINE+BOTS_MAXIMUM-1`.
- `source/game/players.c`: `players_update_before_game` gets actions
  from `update_client_dequeue`, then overwrites bot actions locally.
- `port/linux/game/network_distributed.c`: the host relays player
  actions from `update_server_update_actions` history and sends every
  player's authoritative unit state. A bot action generated locally in
  `players_update_before_game` is **not automatically** the same input
  recorded in that server history. Networked bots must bridge this.
- `port/linux/game/network_objects.c`: host-authoritative object
  creation, deletion, inventories and reconciliation for network clients.
- `port/linux/game/network_actors.c`, `network_coop.c`: host-only
  AI actors already work in co-op campaigns; a future Covenant/Flood mode
  should reuse the actor path rather than disguise NPCs as fake players.

The author of upstream #367 explicitly calls networked bots out as a
follow-up that must notify clients which players are bots and may need a
network-version change.

## Multiplayer Spartan bots: first deliverable

Start with exactly **one** opt-in bot in a System Link **Slayer** lobby;
increase to 2, then 4 only after join, action-replication, kill/respawn,
and round transitions succeed.

1. **Authority and eligibility.** Only a genuine server may create bot
   players or evaluate their AI. Default remains disabled; ordinary clients
   cannot enable bots for the host or impersonate bots. The new network
   setting is distinct from existing `bots.count`, which must continue
   to control offline split-screen games unchanged. Do not enable in
   campaign co-op, online games or other modes in the first prototype.
2. **Roster and slot allocation.** A bot must be represented consistently
   in the host's lobby roster and each connected client's roster.
   Synthetic machine indices 113..127 cannot collide with real machines.
   Reserve slots explicitly or allocate only genuinely unused slots;
   if insufficient capacity, reduce bot count instead of ejecting players.
   Keep the `player_list_index` and player identifiers stable across
   game start, late join, death and match transition. No phantom network
   endpoint must be treated as a connected remote machine.
3. **Input generation.** Evaluate each bot's `bots_bot_update` on the
   authoritative host's 30 Hz simulation. Give the generated actions to
   **both** the local simulation and the host's distributed relay history,
   with matching tick order and valid player index. Clients consume
   relayed actions and unit states but **never** run bot AI locally.
   Account for a missing, stale or out-of-order action without inventing
   a new local bot controller.
4. **State replication.** The host spawns, damages, kills and respawns
   bots; connected clients reconcile their units, weapons, inventories,
   projectiles, grenades and scoring normally. Replication must not depend
   on a nonexistent synthetic client network socket. Late join must get
   bot player and unit state before incremental updates; disconnects
   should not orphan bot units.
5. **Protocol compatibility.** If a change affects the messages, semantics
   or agreement required between machines, bump
   `HALO_PORT_NETWORK_VERSION` with an explicit rationale and enforce
   build compatibility. Never silently mix with the known working APK.
6. **Configuration/UI.** Start as a host-side explicit opt-in, e.g.
   `bots.system_link_count = 1` with a cap of 4 in the prototype.
   Keep ordinary `bots.count` unchanged. Add a friendly in-app host
   UI setting after the engine behavior passes; the client should display
   the authoritative lobby's bot count without being able to change it.

## Verification gates, in order

- Test the pure bot-eligibility and synthetic-machine allocator functions
  with all bot counts, full lobbies, colliding real machine slots, bad
  indices and a client pretending to be host. All default/offline negative
  controls should remain unchanged.
- Test the actual event path: lobby bot addition, roster transmission,
  host-only AI tick, relayed action, client unit state, respawn/death,
  scoreboard and cleanup. No asset-free fake test by itself qualifies
  real gameplay.
- Run existing asset-free `tools/harness` and targeted netcode tests,
  full Linux/Android build in safely isolated environments (never a
  privileged Forge build of untrusted source).
- Test one Linux host + real client with a legally obtained local map,
  capture both logs and verify bot shoots, walks and damages client.
- Verify 0/1/2/4 bots and two/three physical Android phones on the same
  hotspot. Cross-check frame times and bot action receipt in client logs;
  test one bot death, host player death, client respawn, late join,
  leaving/rejoining and whole-match stability.
- Only after the source gates: peer review, upstream issue/PR description,
  source license checks, and a **separate** small upstream PR. Never
  represent a simulated test as a successful network game.

## Follow-up: Covenant and Flood Firefight

Different technical lane from player-style bots:

- Multiplayer maps typically contain walkable surfaces, but lack
  campaign AI encounters, spawn markers, firing positions and scripted
  mission contexts. Inventory/definitions and legal map-data sources
  must be resolved rather than injecting unspecified asset indices.
- First prove a **host-owned single grunt actor** can spawn on a stock
  multiplayer map, path, shoot and be seen/killed by a real LAN client.
- Add constrained spawn sites and navigation validation; then waves,
  faction selection (Covenant / Flood / mixed), difficulty, team rules,
  despawn/cleanup, client late join and performance caps.
- Separate networked actors' authoritative simulation from player bots;
  reuse existing `network_actors.c` replication when it supports the
  chosen game mode. No copies of copyrighted map assets in the APK or PR.
- First public game type should be opt-in (off by default) and not alter
  the classic Slayer or campaign rules.

## CPU budget and reversibility

Initial target: four player-style bots, single host, <=3 AI path searches
per tick as already budgeted by `bots.c`; collect host tick time and
frame-time metrics before claiming a performance number. Avoid per-client
AI work and keep bot count under the host's hardware budget. Optimize only
on measured bottlenecks, preserving deterministic simulation behavior.

Maintain distinct branches for v1 LAN fix, v2 three-phone UX, networked
Spartan bot prototype, and later Firefight. Leave shipping APKs and
game data untouched until independently qualified.
