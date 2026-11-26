"""Original title cache uses the game's real renderer and mixer, not new assets."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
J=ROOT/"port/android/app/src/main/java/com/halo/decomp"
class OriginalTitleTest(unittest.TestCase):
    def test_guest_title_only_mode(self):
        host=(ROOT/"port/android/host/host_main.c").read_text()
        widget=(ROOT/"source/interface/ui_widget.c").read_text()
        attract=(ROOT/"source/interface/attract_mode.c").read_text()
        imports=(ROOT/"port/android/host_imports.list").read_text()
        self.assertIn('argc == 2 && !strcmp(argv[1], "--launcher-title-capture")',host)
        self.assertIn('environment_set(&environment, "HALO_LAUNCHER_TITLE_CAPTURE", "1")',host)
        self.assertIn('if (host_title_capture_mode)',host)
        self.assertIn('environment_set(&environment, "HALO_NET_ONLINE", "false")',host)
        self.assertIn('environment_set(&environment, "HALO_BOTS", "0")',host)
        self.assertIn('getenv("HALO_LAUNCHER_TITLE_CAPTURE")',widget)
        self.assertIn('host_android_title_ready();',widget)
        self.assertIn('scripted_looping_sound_start(sound_definition_index, NONE, 1.0f)',widget)
        self.assertIn('sound\\\\music\\\\title1\\\\title1',widget)
        self.assertIn('we_are_at_the_main_menu && widget_globals.main_menu_music_active &&',widget)
        self.assertIn('ui_widgets_inhibit_processing(TRUE)',widget)
        self.assertIn('if (ui_launcher_title_capture_active())',attract)
        self.assertIn('host_android_title_ready\n',imports)

    def test_audio_is_actual_local_mixer_only(self):
        s=(ROOT/"port/android/host/host_sdl.c").read_text()
        self.assertIn('if (!host_title_capture_mode) return;',s)
        self.assertIn('title_record_mix(&binding->spec, binding->buffer, binding->buffer_length)',s)
        self.assertIn('spec->format != SDL_AUDIO_F32',s)
        self.assertIn('TITLE_MAX_SAMPLES',s)
        self.assertIn('O_EXCL | O_NOFOLLOW',s)
        self.assertIn('title_audio_audible >= TITLE_RATE * 2',s)
        self.assertIn('Java_com_halo_decomp_HaloActivity_finishTitleAudio',s)
        self.assertIn('atomic_store(&title_audio_recording, 0)',s)

    def test_automatic_preparation_no_button_or_startup_loop(self):
        launcher=(J/"LauncherActivity.java").read_text()
        files=(J/"TitleBackdropFiles.java").read_text()
        method=launcher.split("private void maybePrepareOriginalTitle()",1)[1].split("private void buildLanInterface()",1)[0]
        for guard in ("!launcherResumed", "isFinishing()", "isDestroyed()", "!haveData()",
                      "repairBusy || gameProcessStatus() != 0", "pendingVideoLaunch != null",
                      "hasInternetInvite()", 'new File(dataRoot, "join_link.txt").exists()'):
            self.assertIn(guard,method)
        self.assertIn('TitleBackdropFiles.shouldPrepare(titleBackdropAvailable,',method)
        self.assertLess(method.index('putLong("attempted_version", version).commit()'),
                        method.index('startActivity(new Intent(this, HaloActivity.class)'))
        self.assertIn("attemptedVersion != installedVersion",files)
        self.assertIn("!ready && installedVersion >= 0",files)
        self.assertNotIn('prepareTitle.setText("ORIGINAL TITLE")',launcher)
        self.assertNotIn('setTitle("Use the original Halo title?")',launcher)
        self.assertIn("handler.post(this::maybePrepareOriginalTitle)",launcher)
        self.assertIn("maybePrepareOriginalTitle();",launcher)

    def test_capture_and_launcher_lifecycle(self):
        activity=(J/"HaloActivity.java").read_text()
        capture=(J/"TitleBackdropCapture.java").read_text()
        launcher=(J/"LauncherActivity.java").read_text()
        backdrop=(J/"LauncherBackdrop.java").read_text()
        self.assertIn('new String[] { "--launcher-title-capture" }',activity)
        self.assertIn('if (titleCaptureMode) {\n            showTitlePreparation();\n            return;',activity)
        self.assertIn('if (!titleCaptureMode || titleReturning) return;',activity)
        self.assertIn('if (titleForeground)',activity)
        self.assertIn('PixelCopy.request(source, bitmap',capture)
        self.assertIn('copied < TitleBackdropFiles.FRAMES / 6 || changes < 12',capture)
        self.assertIn('activity.finishTitleAudio(true)',capture)
        self.assertIn('TitleBackdropFiles.commit(root)',capture)
        self.assertNotIn('MediaProjectionManager',capture)
        self.assertIn('repairBusy || gameProcessStatus() != 0',launcher)
        self.assertIn('TitleBackdropFiles.prepare(getFilesDir())',launcher)
        self.assertIn('stage.addView(titleBackdrop',launcher)
        self.assertIn('stage.addView(root',launcher)
        self.assertIn('titleBackdrop.pauseTitle()',launcher)
        self.assertIn('titleBackdrop.resumeTitle()',launcher)
        self.assertIn('player.setLooping(true)',backdrop)
        self.assertIn('audio.abandonAudioFocusRequest(focus)',backdrop)
        self.assertIn('generation != token',backdrop)

if __name__=="__main__":
    unittest.main()
