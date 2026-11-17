# Halo v40 — UNSC Service Rank, Spartan Rating, approved artwork

Branch `halo/unscrating-artwork-v40`, derived from tested v39. No upstream
pull request or merge to the user's public fork's default branch.

## Career progression — UNSC Service Rank

The original 12-rank, long-lived XP progression stays intact. Only its
presentation and badge drawing are refined. Every rank and threshold comes
from `CareerRecords`: Recruit 0, Private 1,000, Corporal 3,000,
Sergeant 6,500, Staff Sergeant 12,000, Lieutenant 20,000,
Captain 32,000, Major 50,000, Colonel 80,000, Brigadier 125,000,
General 190,000, Legend 300,000 XP.

The actual UI uses original vector badges, not bitmap screenshots of the
concept poster. v40 aligns the motifs with the approved rank board:
one dot, one bar, two chevrons, three chevrons, chevrons + star,
one diamond, vertically stacked diamonds, one star, vertically stacked
stars, wreath + star, wreath + three stars, gold wreath + diamond + crown
stars. The existing main profile and complete Service Record render them
in scalable views; the new browsable gallery shows all 12 with XP
milestones and highlights the current rank. This avoids image resolution
drift and duplicate copies of rank definitions.

## Separate Spartan Rating

Spartan Rating is analogous to a chess skill rating, not an XP title.
The UI shows **UNRATED** without fabricating a skill score from
self-reported historical kills, offline bot matches, or completed career
games.

Future competitive mode recommendation: a Glicko/TrueSkill-style
uncertainty-aware model for verified human-only competitive sessions.
A signed host match receipt must identify every player, the exact match
ID and human-only participants, team/placement outcome, game-type rules,
and duration, and be independently verified by peers. Only after enough
validated matches can the score become provisional or rated. Discard
duplicate or disputed outcomes, short aborted matches, bots/AI matches,
and unsigned local ledger rows. Before implementation, test repeated
opponent farming, rematches, disconnections, uneven teams, ties, new
players, and misconfigured hosts.

**No ratings protocol or server is activated in v40.** This is a visible
status boundary in the two-tier profile system. No GitHub or internet
backend; the locally saved progression remains compatible with v37–v39.

## Approved Android icon

Use the user-approved original full-color helmet-and-ringworld picture
(`halo_selected_icon.webp`, 512 x 512) in the Android adaptive icon.
The source image is a visually faithful 512-pixel optimized version of
the chosen artwork; SHA-256 source hash is pinned by a release gate:
`937b3876cf0da75724878158b2e604268bfdd20d2cd59c82984dddbd0a348ee8`.

The adaptive foreground insets the chosen art by 18dp on all sides so
Android's masked 72dp safe region still displays the full chosen design.
The monochrome/themed icon uses an original simplified line drawing of
the same helmet shape. The dark-blue native theme background remains.
`mipmap-anydpi-v26/ic_launcher.xml` references only these v40 resources.

The signed release must pass `verify_android_icon_v40.py`, which
confirms the chosen image is byte-exact in the APK (even if AAPT renames
resources), alongside the existing exact-font and native game-ELF gate.
The older APK stays available as rollback reference; update in place
instead of uninstalling.

## Tests and real-device acceptance

Run v40 source tests, v39 UI, existing 29+ regression suites, pure-Java
career/map-transfer tests and full Android Java compilation. Then a
single Android-only exact-head signed package build (versionCode 40).
Verify signing certificate, package ID, binary hash, icon source and
adaptive icon resource, all packaged fonts and game image before phone
transfer.

On Android: install as **Update**; verify app launcher icon after system
cache refresh, profile rank badge and 12-rank gallery, career record and
XP unchanged, Spartan Rating **Unrated**, no obsolete bottom paragraph,
and Host/Join/Phantom gameplay still reachable.
