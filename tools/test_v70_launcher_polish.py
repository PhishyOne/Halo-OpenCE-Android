"""Compact outlined setup groups, adjacent sharing checks and diagnostic title fallback."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
J=ROOT/"port/android/app/src/main/java/com/halo/decomp"
class LauncherPolishTest(unittest.TestCase):
    def test_borders_without_layout_padding(self):
        s=(J/"LauncherActivity.java").read_text()
        helper=s.split("private void setupGroupBorder(View group)",1)[1].split("private void ",1)[0]
        self.assertIn("android.graphics.Color.TRANSPARENT",helper)
        self.assertIn("border.setStroke(Math.max(1, dp(1))",helper)
        self.assertIn("group.setForeground(border)",helper)
        self.assertNotIn("setPadding(",helper)
        self.assertNotIn("addView(",helper)
        for group in ("hostConnection","hostBotOptions","joinPanel","recordingPanel",
                      "advanced","offlineType","offlineBotOptions","offlineTeamOptions","killstreakPanel"):
            self.assertIn("setupGroupBorder("+group+");",s)
        self.assertIn('botChoice(hostBotOptions, "BOT COUNT"',s)
        self.assertIn('botChoice(hostBotOptions, "DIFFICULTY"',s)
        self.assertIn("setupChecks(hostConnection)",s)
        self.assertIn("hostConnection.addView(hostAddress)",s)
        self.assertIn("setupGrid(lanPanel, roleColumn, networkColumn)",s)
        self.assertIn("setupGrid(offlinePanel, offlineLeft, offlineRight)",s)

    def test_video_checkbox_precedes_career_and_preserves_state(self):
        s=(J/"LanBoardUi.java").read_text()
        self.assertLess(s.index("shareControls.addView(videoSharing);"),
                        s.index("shareControls.addView(sharing);"))
        self.assertIn('videoSharing = sharingCheck("LAN VIDEO SHARING")',s)
        self.assertIn("videoSharing.setChecked(state.videoAllowed)",s)
        self.assertIn("LanShareSettings.setVideos(activity, enabled)",s)
        self.assertIn("LanShareSettings.setCareer(activity, enabled)",s)
        self.assertIn("LAN VIDEO SHARING (PAUSED)",s)
        self.assertNotIn("addVideoOptOut",s)
        self.assertNotIn("videoOptOut",s)
        self.assertIn("this.reels = career && videoAllowed",(J/"LanShareState.java").read_text())

    def test_title_waits_for_surface_and_reports_failures(self):
        cap=(J/"TitleBackdropCapture.java").read_text()
        player=(J/"LauncherBackdrop.java").read_text()
        launcher=(J/"LauncherActivity.java").read_text()
        self.assertIn("surfaceDeadline = SystemClock.elapsedRealtime() + 12000",cap)
        self.assertIn("ui.postDelayed(this::awaitSurface, 150)",cap)
        self.assertIn("source.getHolder().getSurface().isValid()",cap)
        self.assertIn("copiedFrames = copied",cap)
        self.assertIn("changedFrames = changes",cap)
        self.assertIn("capturedAudioMs = audioMs",cap)
        self.assertIn("TitleBackdropFiles.writeStatus(activity.getFilesDir(), detail)",cap)
        self.assertIn("player.setDataSource(input.getFD())",player)
        self.assertIn('reportFailure("Title animation playback failed',player)
        self.assertIn("toolsPage.addView(titleStatusView)",launcher)
        self.assertIn("titleStatusView.setVisibility(failure ? View.VISIBLE : View.GONE)",launcher)
        self.assertIn("TitleBackdropFiles.readStatus(getFilesDir())",launcher)
        self.assertNotIn('prepareTitle.setText("ORIGINAL TITLE")',launcher)

if __name__=="__main__":
    unittest.main()
