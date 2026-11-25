# Halo v56 — Manage local video storage

Profile → WATCH / SAVE / DELETE LOCAL VIDEOS → MANAGE VIDEOS.
Select one or more entries or DELETE ALL. The list shows size, date,
type and aggregate storage. Deletion is permanent, requires explicit
confirmation and reports successful count, bytes and skipped files.
It remains accessible when no playable files exist, so even a corrupt
completed recording with a recognized name can be removed.

Scope: exact completed private MP4 names only; no directories, links,
active .pending captures, maps, campaign saves, career statistics,
highlight event history, or exported copies. User may delete originals
without deleting their existing reels, and vice versa. Deletion uses a
size/modified snapshot, shares the assembly lock, and does not modify
the recorder. A zero-byte marker per explicitly deleted reel prevents
automatic regeneration from an original kept for other highlights.
Session index metadata is retained; absent originals are skipped.

Device acceptance: verify list counts/sizes, cancel leaves all files,
delete one reel and reopen the launcher without regeneration, keep
the original independently, delete a full recording, and confirm
DELETE ALL removes only the listed completed videos. Exported copies
remain outside Halo's control. Do not delete real user footage during
automated testing; use temporary fixtures.
