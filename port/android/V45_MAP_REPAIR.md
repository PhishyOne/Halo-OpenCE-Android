# v45: map checks and automatic repair

Includes v44's screen-off LAN protection and durable unfinished-match accounting.
The native wire protocol remains 54; v44 and v45 speak the same protocol.

The normal Map Repair section now has two primary actions: CHECK MY MAPS and
IMPORT MAPS OR REPAIR ZIP. Checks show a separate row for each of the ten campaign
maps and Sidewinder, with large green "✓ PASS" or red "✕ FAIL" text. Failed rows
offer the replacement picker directly. Sharing and rollback controls are collapsed
under SHARE MAPS & BACKUPS. The stale, hard-coded b40/c10 failure warning is gone.

Imports read each map's internal Xbox cache identity, not the chosen dropdown or
document filename. Multiple .map files and a campaign repair ZIP are accepted.
The importer leaves already-valid installed maps alone, validates replacements
fully, and preserves the earlier copy before installation. Retrying a repair can
retain up to eight backup generations without overwriting an existing backup.
The original Restore Backup action still restores the first .previous copy.

ZIP entries are streamed, never extracted by their supplied paths. The importer
rejects traversal, limits entry counts and sizes, and uses the fixed allowlist of
eleven Xbox levels. A later bad entry does not undo earlier successful repairs;
the result lists both success and failure. After importing, the installed maps
are checked again and the displayed rows refresh automatically. Custom Edition
maps retain their existing separate importer.

On the user's phone, the existing b40.map and c10.map repair sources in
Download/Halo/Campaign-Repairs-Verified passed Xbox identity, full zlib checksum,
decoded length, tag-range and tag-signature validation. c10 is 343 Guilty Spark;
b30 is The Silent Cartographer. The screenshot showed c10 being imported while
the old selector still targeted b30. This manual selection no longer controls
imports.

Validation: existing repair tests (59 assertions), campaign transfer/archive
tests (123 checks), and new automatic import tests (26 checks) pass. The new tests
cover misleading ZIP filenames, repeated repair/backup preservation, valid-map
skips, corrupt members, partial success, traversal, entry/byte limits, and the
structured PASS/FAIL results. The 134-test source regression suite plus 78 subtests
passes, and all Android Java sources compile against API 35. v44's full Android
build passed at 2af14fcef9f676713d9e5b1b56b1a4b9a0f5be32; v45's exact source/build
and APK signature evidence are recorded in the delivered build report.

Phone acceptance: install v45, open Tools > Map Repair, import both files from
Campaign-Repairs-Verified, and verify their rows become green. Test actual gameplay
and host/guest lock-unlock separately; a file PASS is not a gameplay test. Native
screen-off and disconnect limitations remain documented in V44_BACKGROUND_AND_EXITS.md.
