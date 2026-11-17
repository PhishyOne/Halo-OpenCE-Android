# Menu pictures that are not redraws

The menus' bitmaps are drawn from the high-res redraws (`svg/`, from ui-svg-handmade) where there is
one. The PC version's pictures that have none (3D renders, screenshots and logos) are not in this
repository.

## From the Xbox's map

These have the same pictures, in the same order, in the Xbox's `ui.map`, which the menus draw them
from (the player's own; nothing is shipped). `mp_map_grafix`: the Xbox's has the 13 Xbox maps' pictures
(the PC version's first 13) and its "?"; the PC version's other 8 are its own maps'.

- `ui\shell\bitmaps\colors_sm`
- `ui\shell\bitmaps\mp_map_grafix`
- `ui\shell\bitmaps\sp_levels`
- `ui\shell\main_menu\difficulty_select\difficulty_options`
- `ui\shell\main_menu\difficulty_select\difficulty_options_small`
- `ui\shell\main_menu\halo_logo`
- `ui\shell\main_menu\settings_select\player_setup\player_profile_edit\color_edit\player_color_marine_large`

These frames are drawn from a frame of the Xbox map's bitmap of the same name, whose other frames differ:
the same picture, or for the profile settings the Xbox's picture for the same thing.

| The PC version's frame | The Xbox's frame |
| --- | --- |
| `shell/main_menu/multiplayer_type_select/mp_options__2.png` | `ui\shell\main_menu\multiplayer_type_select\mp_options` frame 3 |
| `shell/main_menu/settings_select/multiplayer_setup/playlist_edit/gametype_options__2.png` | `ui\shell\main_menu\settings_select\multiplayer_setup\playlist_edit\gametype_options` frame 2 |
| `shell/main_menu/settings_select/multiplayer_setup/playlist_edit/gametype_options__5.png` | `ui\shell\main_menu\settings_select\multiplayer_setup\playlist_edit\gametype_options` frame 4 |
| `shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__0.png` | `ui\shell\main_menu\settings_select\player_setup\player_profile_edit\profile_options` frame 0 |
| `shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__2.png` | `ui\shell\main_menu\settings_select\player_setup\player_profile_edit\profile_options` frame 1 |
| `shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__7.png` | `ui\shell\main_menu\settings_select\player_setup\player_profile_edit\profile_options` frame 3 |

These frames are added after a bitmap's, drawn scaled from the Xbox map's (the profile settings'
picture of Gamepad Setup: the Xbox's Controller Setup's pictures of the button settings).

| Our bitmap | The Xbox's frame |
| --- | --- |
| `main_menu/settings_select/player_setup/player_profile_edit/profile_options` | `ui\shell\main_menu\settings_select\player_setup\player_profile_edit\controller_edit\config_controller` frame 4 |
| `main_menu/settings_select/player_setup/player_profile_edit/profile_options` | `ui\shell\main_menu\settings_select\player_setup\player_profile_edit\controller_edit\config_controller` frame 5 |
| `main_menu/settings_select/player_setup/player_profile_edit/profile_options` | `ui\shell\main_menu\settings_select\player_setup\player_profile_edit\controller_edit\config_controller` frame 6 |
| `main_menu/settings_select/player_setup/player_profile_edit/profile_options` | `ui\shell\main_menu\settings_select\player_setup\player_profile_edit\controller_edit\config_controller` frame 7 |
| `main_menu/settings_select/player_setup/player_profile_edit/profile_options` | `ui\shell\main_menu\settings_select\player_setup\player_profile_edit\controller_edit\config_controller` frame 8 |

## Placeholders, to be redrawn

The Xbox's map has none of these (or other pictures under the name), so each frame is a placeholder
until it is redrawn.

| File | Size | The PC version's picture |
| --- | --- | --- |
| `ce/shell/main_menu/multiplayer_type_select/mp_options__0.png` | 512x256 | `shell/main_menu/multiplayer_type_select/mp_options__0.png` |
| `ce/shell/main_menu/multiplayer_type_select/mp_options__1.png` | 512x256 | `shell/main_menu/multiplayer_type_select/mp_options__1.png` |
| `ce/shell/main_menu/settings_select/multiplayer_setup/playlist_edit/gametype_options__0.png` | 512x256 | `shell/main_menu/settings_select/multiplayer_setup/playlist_edit/gametype_options__0.png` |
| `ce/shell/main_menu/settings_select/multiplayer_setup/playlist_edit/gametype_options__3.png` | 512x256 | `shell/main_menu/settings_select/multiplayer_setup/playlist_edit/gametype_options__3.png` |
| `ce/shell/main_menu/settings_select/multiplayer_setup/playlist_edit/gametype_options__4.png` | 512x256 | `shell/main_menu/settings_select/multiplayer_setup/playlist_edit/gametype_options__4.png` |
| `ce/shell/main_menu/settings_select/multiplayer_setup/playlist_edit/gametype_options__6.png` | 512x256 | `shell/main_menu/settings_select/multiplayer_setup/playlist_edit/gametype_options__6.png` |
| `ce/shell/main_menu/settings_select/multiplayer_setup/playlist_edit/gametype_options__7.png` | 512x256 | `shell/main_menu/settings_select/multiplayer_setup/playlist_edit/gametype_options__7.png` |
| `ce/shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__1.png` | 512x256 | `shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__1.png` |
| `ce/shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__3.png` | 512x256 | `shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__3.png` |
| `ce/shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__4.png` | 512x256 | `shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__4.png` |
| `ce/shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__5.png` | 512x256 | `shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__5.png` |
| `ce/shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__6.png` | 512x256 | `shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__6.png` |
| `ce/shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__8.png` | 512x256 | `shell/main_menu/settings_select/player_setup/player_profile_edit/profile_options__8.png` |

13 of 183 frames are placeholders.

The PC version's `ui\gamespy` and `ui\ticker` fonts are drawn with `ui\small_ui`, which
the Xbox's map has.

## Optional v47 custom-map preview pack (separate user data)

Genuine gameplay screenshots from the map archive are converted to plain
BMP files for the existing custom-map picture reader. They are not bundled
into the application or claimed as original art. Map imagery belongs to its
respective creators and Halo rights holders. Archive: Halo Maps, Mostly
Harmless Productions. Filename-to-page attribution:

- urban_warfare_beta: https://www.halomaps.org/hce/detail.cfm?fid=974
- municipality (Wave of Lag): https://www.halomaps.org/hce/detail.cfm?fid=1700
- the_narrows (Peter Sakievich): https://www.halomaps.org/hce/detail.cfm?fid=1053
- h2_warlock (H2CE Team): https://www.halomaps.org/hce/detail.cfm?fid=1152
- h2_zanzibar (H2CE Team): https://www.halomaps.org/hce/detail.cfm?fid=1153
- h2-foundation (Xe-Bec): https://www.halomaps.org/hce/detail.cfm?fid=1240
- h2_headlong: https://www.halomaps.org/hce/detail.cfm?fid=1225
- h2-lockout_final (Nathan555): https://www.halomaps.org/hce/detail.cfm?fid=1740
- camtrack-arena-fx (HILancer): https://www.halomaps.org/hce/detail.cfm?fid=1624
- immure (Mothergoat / Neuro ko neza): https://www.halomaps.org/hce/detail.cfm?fid=1278
- yoyorast_island (Yoyorast): https://www.halomaps.org/hce/detail.cfm?fid=1144
- beach_assault (FalseProphetNemesis modification): https://www.halomaps.org/hce/detail.cfm?fid=382
- deathisland (Bungie/Gearbox), screenshot: https://www.halopedia.org/Death_Island
- icefields (Bungie/Gearbox), screenshot: https://www.halopedia.org/Ice_Fields
- gephyrophobia (Bungie/Gearbox), screenshot uploaded by Hides-His-Tail:
  https://www.halopedia.org/File:Halo_Combat_Evolved-Gephyrophobia.jpg

The pack guide records the exact selected image URLs and SHA-256 hashes.
Screenshots identify the downloaded map versions by filenames and archive
listings, not a proof of each installed map's byte identity or playability.
