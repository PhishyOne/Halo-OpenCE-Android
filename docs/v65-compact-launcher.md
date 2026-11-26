# Halo v65 — Compact landscape launcher

Offline Match: left column Bot Match/Campaign, count and difficulty;
right column teams and killstreak options. Campaign hides all bot and
reward controls. Host: left IP, APK sharing, LAN-only and bot controls;
right network address override and rewards. Join: left discovery,
manual host IP, update and LAN-only; right network address override and
global recording options. Driving is enabled on LAN host launch without
a checkbox; join/online still do not activate host bots. The shared
LAN-only and rewards views are moved, not cloned, preserving state.

Shorter header/tabs/Play footer, 34dp rows in short landscape windows
and 42dp otherwise. The empty status row is gone; error text remains.
Keep the ScrollView as a fallback for small windows, increased font
sizes, invitation status and keyboard. Do not hide or clip options to
force a fit. No-scroll acceptance requires actual device measurements.

Tools: two weighted columns. Left map repair and map backups; right
custom maps, campaign saves and diagnostics. Audit details span the
full width below both columns. Existing operation callbacks, repair
guards and confirmation/picker paths preserved. Stop/Pause is visible
but disabled when idle. No disclosure menus. Full regrouping pending
user review of inventory below.

Tools inventory (17 controls/actions):
1. Check my maps — validate 11 original files.
2. Share my maps — serve validated maps locally.
3. Repair from host — download only needed maps, resume interrupted data.
4. Stop / Pause — stop sharing or pause active repair.
5. Import maps or repair ZIP — verified original maps, local documents.
6. Map to share or restore — choose campaign map or Sidewinder.
7. Export admin repair library — validated campaign map archive + inventory.
8. Export map — selected map, local file.
9. Restore backup — swap selected map with preserved previous copy.
10. Import maps or preview files — individual/multiple Custom Edition files.
11. Import a map folder — Android directory grant.
12. Import map / preview ZIP — packaged custom map/resources/previews.
13. Check Beach Assault resources — read-only tag/resource audit.
14. Back up campaign progress — profiles and checkpoints ZIP.
15. Restore campaign backup — validate and restore with rollback.
16. Save diagnostic report — local redacted network/map log file.
17. Choose replacement — conditional action on failed original map report.

No gameplay/network protocol, rating, recording or data format changes.
Device acceptance: verify no ordinary scrolling on Offline/Host/Join on
landscape phone; check all controls are readable/tappable, keyboard and
large text fallback, mode/role switching and saved preferences, host
bot driving enabled, Join recording controls global, Tools all actions
visible, disabled idle pause and active transfer, audit reports and
confirmation/backup safety. Phone layout remains unverified until tested.
