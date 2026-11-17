# v46: repair original maps from a local host

Both phones use v46 and remain in Tools > Map Repair on the same hotspot/Wi-Fi.
The host taps SHARE MY MAPS and waits for validation. The receiver taps REPAIR
FROM HOST. One discovered sharing host is selected automatically; multiple hosts
produce a chooser. If discovery is blocked, the receiver can enter the address
shown on the sharing phone. STOP SHARING closes host listeners; PAUSE REPAIR keeps
unfinished bytes. Tap REPAIR FROM HOST again to resume. Stop sharing before Play.

The host offers only validated original Xbox campaign maps and Sidewinder
(11 allowed IDs). Missing/failed host files are omitted, and the offered count
is shown. The receiver skips healthy installed maps, verifies the full downloaded
SHA-256 and Xbox cache, then uses the existing backup-preserving importer.
Completed repairs stay installed if a later map fails. Installed maps are checked
again afterward, using v45's green PASS/red FAIL rows. A structural file check is
not proof of gameplay or rendering compatibility.

One partial file is retained, identified by map ID, total length and SHA-256.
Retrying from a new host address resumes if the offered bytes are identical.
Changing the offered content resets only the old partial. A bad completed checksum
clears the partial and never modifies installed maps. Download and installer
staging space are checked before transferring. No full host-side map copy is made.

The launcher uses protocol HMP1 on TCP 5164, independent of gameplay. Network
version is raised to 55 to accompany the new peer feature. Both directions bind
eligible private Wi-Fi/hotspot interfaces; the host also checks the peer subnet.
No hostname, arbitrary URL, redirect or peer-controlled filesystem path is used.
Catalogs contain at most 11 fixed-size records; ranges are capped at 4 MiB. Two
workers and four queued clients bound host load. Absolute 30-second connection
deadlines prevent slow peers from keeping a worker/client indefinitely. Host
source size/mtime changes are rejected, and final client hashes catch byte changes.

Sharing is opt-in and ends when the launcher is destroyed or sharing is stopped.
CPU/Wi-Fi leases and a keep-screen-on flag are released at teardown. Keep both
phones on this screen: this is not an unattended background download service.
The trusted local host is selected by discovery/IP; this is not cryptographically
authenticated public distribution. Custom Edition map transfer remains separate.

Validation: 44 Java checks use real TCP sockets, injected disconnection, partial
resume, cancellation, changed host data, forged ranges, bad manifests, checksum
failure, staging symlinks, installation backups and a real timed socket close.
The existing 134 tests and 78 subtests pass. All Android Java sources compile
against API 35; the full web build passes. The final Android build and signing
results are captured in the delivered verification bundle.

Physical acceptance still needed: host/guest discovery on the user's hotspot,
transfer a missing campaign map, interrupt and retry, verify the final green
status, and play the repaired mission. Check two friends downloading at once and
confirm STOP SHARING frees the listener before starting a normal LAN game.

Custom-map preview finding: the native menu already accepts a matching .bmp
beside each custom .map, with .txt descriptions. Android's current custom importer
does not carry these sidecars. Preview importing and sourcing real screenshots
are not implemented by v46.
