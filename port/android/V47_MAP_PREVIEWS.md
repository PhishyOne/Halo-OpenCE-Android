# Halo v47: custom-map screenshots

The Android file, folder and ZIP importers now accept matching `.bmp`
preview files as well as Custom Edition maps and resources. A preview-only
ZIP works with already-installed maps; no large map download is needed.
Open Tools > Custom Maps > Import map / preview ZIP, select the preview
pack, then reopen the game's custom map menu.

Pictures keep the map's basename, e.g. `immure.bmp` beside `immure.map`.
The existing native menu reads the image and fits its center to the map
picture. Preview imports do not modify map bytes or saves, and do not
replace existing pictures. A pack import is per-file, not transactional;
if a later entry fails, earlier successful entries remain installed.

The importer accepts only Windows BITMAPINFOHEADER RGB (uncompressed
24/32-bit), at most 2048 pixels per side and 16 MiB per file. It validates
headers, row lengths and pixel bounds before moving a synced temporary
file into place. The existing ZIP entry, expanded-byte and safe-name
limits remain. Network map validation still accepts only maps; previews
are not transferred automatically with LAN Custom Edition downloads.
Network protocol remains 55. No native engine behavior changed.

The supplied screenshot pack is separate from the APK and source tree.
It uses genuine archive/wiki images, never generated scenery; source URLs
and credits are recorded in its accompanying guide and the menu art register.

Beach Assault: v34's CE tag-root alias fix remains included. The real
15,978,580-byte map passed native tag/BSP validation after the fix, where
it previously failed. See docs/pro_review_v34.md. Physical rendering,
spawning and multiplayer success are still unconfirmed; v47 makes no new
Beach Assault engine change.

Validation: real Java import/ZIP regression tests, malformed BMP/path/
symlink cases and full Android Java compilation. Final Android build and
actual pack/native BMP-reader checks are recorded with the delivered build.
