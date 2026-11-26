# Halo v67 — Simple Tools, map exchange and clear IP labels

Seven main actions: Check My Maps, Repair From Host, Share Maps, Receive
Maps, Import Maps, Back Up Campaign Progress, Restore Campaign Backup.
Two buttons per row, compact progress and failed-only original-map report,
contextual Stop/Pause. Retire visible map restore/export/admin archive,
folder/ZIP-specific import buttons, Beach Assault diagnostic and standalone
diagnostic export. Existing repair rollback and campaign backup validation
remain; old internal helpers/request handlers are retained.

One system picker accepts individual/multiple .map/.bmp or ZIP files.
UnifiedMapImport routes original Xbox v5 by header to existing repair
logic, CE caches/resources/previews to existing strict importer; ZIP
entries use bounded safe basenames and validation. No automatic overwrite
of existing different custom files. Original healthy maps retained.

Share Maps lets user choose verified originals (for repair), custom
maps or resources. Include matching previews and available CE resources
with selected custom maps; receiver explicitly chooses from the offered
catalog. Existing matching files skipped, different installed files stop
the transfer without replacement. Read-only catalog, safe filename
grammar, size cap, bounded 4 MiB range requests, SHA-256 verification,
persistent partial for the same file and checksum, safe installation.
One active transfer/backup/import at a time. Current map sharing uses
TCP 5166, avoiding the existing leaderboard TCP 5164 conflict. Stock HMP1
framing is unchanged; new receivers can try legacy TCP 5164 if needed,
but older receivers need v67 to find a new sharing phone. UDP discovery
remains unchanged. No gameplay network version change. Custom content
must be trusted; structural validation is not a guarantee of playability.

Host displayed address: Your IP. Join: Host's IP address, copy from
host's screen. Local override: Your IP (optional), leave blank for auto.
Map receiver manual IP explicitly refers to the sharing phone;
leaderboard manual entry says Friend's IP (optional).

God Mode voice root cause: reliable native global notice already sent and
guest/Java support ID 14, but host_sdl.c rejected 14 before JNI playback.
Align bridge whitelist with actual supported 3, 5 and 14. No new notice
or duplicate audio. Physical two-phone playback still needs acceptance
with radio announcements enabled.

Test actual Tools layout, IP labels, original check/repair, mixed import,
selected CE map/preview/resource exchange between two v67 phones,
cancel/resume, existing conflicting map, leaderboard during sharing,
backup/restore, both host and client hearing God Mode at 10 kills.
