# Halo v34: reproduced Beach Assault validation failure and vehicle self-occlusion

## Scope

This branch starts at v33 `6376f72` and preserves its ground-vehicle combat,
Warthog crew recruitment, 64 LAN bots, mode objectives, updater and campaign
backup. Protocol remains **52**: no packet layouts, IDs, capacities or
network rules change. All players testing Beach Assault need the corrected
loader; an old client can still reject the map even though ordinary games
remain wire-compatible.

No game, map, resource pack, save or installed APK was modified during the
investigation. Standalone native audit tools read the user's downloaded
files and modify only their own temporary in-memory copies.

## 1. Beach Assault: actual before/after, not a guessed renderer change

Exact original map: 15,978,580 bytes, SHA-256
`16e30cf229fdabcbcf21b8f0b1e57138e0b0cb95f28735fad8bd9506ff00031e`.
A separate archived download was already proven identical. The map is
CE v609 with 2,437 tags, one BSP and 866 external resource references.

The unmodified `cache_file_formats.c` loader and byte conversion both
succeed with the user's actual downloaded bitmaps/sounds/locale maps.
The next stage, the real native tag validator, **refuses the map**:

```
tag 'vehicles\ghost\ghost_mp' (vehi):
has its data at 40707f2c, outside the tags or overlapping another's
```

A raw table inspection confirms three vehicle handles point to the same
complete definition at `0x40707f2c`: Warthog (index 787), Ghost (885), and
Scorpion (1023). Two weapon handles also share the complete definition at
`0x408231f8`: Needler (1406) and Plasma Pistol (1482). These are exact root
aliases, not partially overlapping independent structures.

The old validator claims every root separately, then visits every nested
tree separately. It therefore rejects the second handle even though the
same concrete schema describes exactly the same full definition.

### Narrow correction

For CE vehicle and weapon tags only, keep a bounded temporary root index.
An exact root address with the identical concrete group can share its
already-validated canonical tree. Every handle, parent-group metadata and
name is still checked. The canonical root and all nested data still undergo
the original bounds, overlap, schema, value and cross-reference checks.

Do **not** allow a partial root overlap, cross-type alias, unsupported tag
kind, out-of-bounds root, or arbitrary overlapping nested blocks. Xbox map
validation is unchanged. No pointer is redirected, no map bytes are saved,
and no tag is silently removed. Allocation is a matched game-allocator
malloc/free pair, temporary for the load; maximum under 576 KiB in the
32-bit ABI and about 35 KiB for Beach Assault.

The same map now passes native tag **and BSP** validation: 19 ordinary
corrections plus a diagnostic noting three complete aliases. Alias handling
is not counted as a correction because it does not rewrite data; a second
validation of corrected tags stays clean.

### Native Android evidence

Compiled the actual tag validator, all schema files, cache loader and
vendored zlib into a standalone ARM32 Android binary, preserving ILP32
pointer/long and short-wchar layouts. The user's phone supports ARM32.
Audit-only compatibility headers adapt libc names; game schemas and data
sizes are unchanged, including their compile-time size checks.

The old validator returns **1 (refused)**. A second baseline matching the
game's group-inference order also refuses the same Ghost root. The corrected
validator returns **0** on the same unchanged map/resources.

Final audit binary SHA-256:
`65271c95ea91b8e120983074ecfb07c114a511b1463fe753413f293f67cc9c73`.

A bounded 32-trial mutation run at seed 3400 completed without a crash or
non-idempotent revalidation: **6 refused, 26 corrected**. Death Island and
Icefields both validate before and after, each with one ordinary correction.
The original Beach Assault checksum remains unchanged after all tests.

**Important limit:** the standalone validator does not create GPU buffers,
decode all runtime audio, run scripts, spawn players or render the level.
This fixes a reproduced native load blocker; physical gameplay/rendering
still needs confirmation. Downloaded files passing does not establish that
older app-owned installed resource copies are identical. Keep the v32
installed-resource audit and diagnostic export available for a further fault.

## 2. Unsafe diagnostic text and group-inference bookkeeping

Three pre-existing calls passed an uninitialized stack message buffer to
`tag_validate_report` during group inference / BSP scanning. Remove those
calls; the remaining summary is formatted before reporting. Reset per-map
inference counters and preserve the count of groups inferred from pending
BSP references instead of immediately resetting that count to zero.

The standalone `tools/map_validate.c` now mirrors the runtime ordering:
load, infer protected tag groups, convert, validate. It also provides paired
allocator stubs for its isolated validator build. No validation is skipped.

## 3. Vehicle AI: the bot's own hull blocks perception

`bots_line_of_sight` excluded the seated Spartan datum only. The engine's
`object_test_vector` skips exactly the supplied object subtree, not its
parent. A bot whose head is inside a tank/Warthog hull could therefore see
its own vehicle as the first obstacle and never acquire the enemy.

The sight query now excludes the shooter's **verified parent vehicle**
when seated; on-foot queries still exclude the Spartan as before. It still
casts the normal collision ray through all walls and other vehicles. It does
not blindly turn a self-hull collision into visibility, disable cover, or
change projectile physics, teams, damage or human controls.

An exact-source collision-query fixture fails against the original function
and passes the candidate. It checks own-hull exclusion, preserved walls and
unrelated-vehicle blockers, target-vehicle visibility, invalid/nonvehicle
parents and unchanged infantry sight. Deliberately removing the parent or
vehicle-type guard makes the fixture fail. Actual tank/turret gameplay is
not simulated by this fixture and still needs an on-device match.

## Regression gates

New tests compile actual production functions, not copied implementations:

```
python3 tools/test_ce_tag_aliases.py -v
python3 tools/test_bot_vehicle_visibility.py -v
```

Seven tests include adversarial negative controls. The alias fixture covers
complete vehicle and weapon roots, Xbox rejection, partial/cross-type/root
bounds checks, nested overlap rejection, bad handles, allocation failure,
cleanup and 1,024 aliases validated once. Strict Clang builds passed locally;
CI also runs them with the hosted compiler before Android native compilation.
All 22 existing Python workflow entry points passed locally, including
vehicle crews, flips, modes, killstreaks, LAN counts, updater and saves.

Evidence on Forge is under
`/mnt/nomatoka-terrain-scratch/halo-pro-v34-evidence/`.
Phone reports and temporary audit executables are under
`~/.cache/halo-pro-v34-audit/`. Neither directory is active Halo storage.

## Physical acceptance

Install the same-signed v34 APK with **Update**, never Uninstall. First
try a private Beach Assault game with no bots, then a small bot match and
one joining phone. Verify level rendering and spawning before a 64-bot run.
All Beach Assault participants should run v34's corrected loader.

For vehicles, try a bot Scorpion/Ghost engaging an enemy and slowly drive
a friendly Warthog near bots with its turret empty. This build also includes
the v33 firing/recruitment fixes which were not installed on the user's v32
phone at the start of this run. Preserve logs if either behavior fails.
