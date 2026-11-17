# Halo v38 — Spartan Profiles, Phantom Reward, Launcher Cleanup

Development branch: `halo/ui-progression-v38` in the user's public fork,
`PhishyOne/Halo-OpenCE-Android`. No upstream OpenCE pull request, publication
to the originators, or merge to the fork's main branch.

## Default launcher

- **Profile** is the first, automatically opened launcher tab. It reads the
  local career journal asynchronously and shows Spartan name, XP/rank progress,
  matches, wins, kills/deaths, K/D, best streak and unlocked medals.
- **Offline Match** is the second tab, followed by Campaign, Host, Join and
  Tools. Play stays hidden while Profile or Tools is active.
- Expand/collapse controls remain highlighted while the panel is open:
  Host Tips, Network Options, Killstreak Rewards, Map Repair, Custom Maps,
  and Campaign Saves.
- Host tab shows the detected local IP as a 19sp conspicuous label; no
  clipboard or system share controls.
- Join uses automatic discovery and a directly visible IP input for manual
  fallback. Automatic update check and version verification are unchanged.
- Retired 2142 Commander settings are removed from Android launcher and
  forcibly disabled when launching the game. Android's effective feature
  policy also masks out Commander and Orbital permissions from stale
  config or legacy hosts. Existing protocol numbers remain reserved for
  compatibility. Non-Android ports keep prior policies.

## Original Xbox map repair

The two obsolete dedicated Sidewinder and b30 (Silent Cartographer)
pickers/handlers are removed. The **Verified Xbox Map** selection now handles
the ten stock campaign maps **and** Sidewinder, all through
`SidewinderRepair` validation and .previous rollback semantics. The
Custom Edition importer remains separate (its v609 format differs).
All ten campaign maps can be audited, with explicit export/import using
Android's document picker.

The screenshot supplied before the update shows **8/10 passing**, and
`b40.map` / `c10.map` present but failing Xbox-cache header validation.
These must be replaced with correct original Xbox cache files. Neither
the audit nor importing the same incorrect files repairs them.

## 10-kill "Phantom" / God Mode

- Killstreak rewards are still host-authorized and opt-in; the old
  3-kill enhancement and 5-kill recon remain.
- On **10 enemy kills without dying**, the server gives the earning human:
  active camouflage for **1,800 game ticks = 60 seconds**, plus
  `_object_cannot_take_damage_bit` damage immunity while their precise
  player ID and unit remain valid.
- Duration doesn't stack. Deactivation on expiry, death, new unit, leaving,
  disabling rewards, or end of match removes only damage immunity that the
  reward itself acquired, and never clears a pre-existing immunity.
- Host sends one reliable public notice to all players when announcements
  are enabled. Its text announces that the Phantom is invisible and
  invulnerable for 60 seconds. Every Android phone plays an original
  three-pulse warning followed by a British English female voice saying
  **"God Mode Enabled."** This is a single WAV assembled offline with the
  locally verified Kokoro voice model. No synthesis or internet needed
  on the players' Android phones.
- Normal weapon/explosion damage uses the game's cannot-take-damage bit.
  Script-forced deaths, map kill volumes and network desync still require
  actual match testing. Do not claim immunity against those until verified.
- The old orbital attack is not awarded, not called from the reward loop,
  and no longer has launcher controls or active Android capability flags.
  Legacy message identifiers are reserved rather than renumbered.

## Long-lived progression

12 ranks from Recruit to Legend now require thresholds:
`0, 1000, 3000, 6500, 12000, 20000, 32000, 50000, 80000, 125000,
190000, 300000` XP. Earned XP stays backed by the same append-only
local match journal, so upgrading does not discard v37 history.
The display recalculates ranks at the higher thresholds.

19 achievements, four rarities:

- **Common:** First Blood, Enlisted, First Victory
- **Uncommon:** Bot Buster, Human Hunter, Team Player, Flag Runner,
  Spartan Spree
- **Rare:** Untouchable, War Veteran, Champion, Flag Hero, Perfect Game,
  Slayer Elite
- **Legendary:** The Immortal, War Eternal, Human Reaper, Supreme
  Commander, Flag Emperor

Rare and legendary thresholds demand sustained long-term play. Bot kills
still have capped XP contribution; competitive skill ratings and synced
leaderboards **are not** included here. All profile data stays locally on
each phone; it is not uploaded to GitHub when the source is pushed.

## Verification and field checks

Source/JDK/native harness and Android SDK Java smoke checks are required.
One Android-only exact-head hosted build is run if those pass, with all
other platform jobs skipped and no uncontrolled Forge native build.
APK signing identity/package, increasing version code 38 and SHA-256
must match before staging `Download/Halo/Halo-v38.apk`.

On the phone, install as **Update**, never Uninstall. First open Profile,
then check Offline Match and Host/Join navigation. For the Phantom award,
enable Killstreak Rewards on the host and complete ten enemy kills without
dying; verify alarm + spoken warning on every phone, 60-second stealth,
actual inability to take damage, and removal exactly at expiry/death.
No claim of successful LAN field testing until the user confirms it.
