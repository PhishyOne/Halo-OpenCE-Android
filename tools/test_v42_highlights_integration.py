"""Contract tests: v42 no video/fake replay, durable moment markers, Xbox repair validation."""
from pathlib import Path
import unittest

R = Path(__file__).resolve().parents[1]
JAVA = R / "port/android/app/src/main/java/com/halo/decomp"
NATIVE = R / "port/linux/game"


class V42SourceContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.career = (NATIVE / "career_record.c").read_text()
        cls.highlight = (NATIVE / "highlight_moments.c").read_text()
        cls.ctf = (R / "source/game/game_engine_ctf.c").read_text()
        cls.reader = (JAVA / "MatchHighlights.java").read_text()
        cls.profile = (JAVA / "CareerProfile.java").read_text()

    def test_postgame_highlights_linked_to_durable_existing_match_id(self):
        self.assertIn('halo_highlights_start();', self.career)
        self.assertIn('halo_highlights_import(result->moments, result->moment_count)', self.career)
        self.assertIn('halo_highlights_finish(root, id, map, result->end_tick, 0)', self.career)
        self.assertIn('if (synced && closed)', self.career)
        self.assertIn('career-matches-v1.log', self.career)
        self.assertIn('career-highlights-v1.log', self.highlight)
        self.assertNotIn('sendto(', self.highlight)

    def test_ctf_scores_recorded_for_actual_capturer_by_host(self):
        self.assertIn('player->statistics.multiplayer_statistics.ctf_statistics.flag_scores++;',
                      self.ctf)
        self.assertIn('career_results_capture(player_index);', self.ctf)

    def test_bounded_and_read_only_in_game_event_sampling(self):
        self.assertIn('HIGHLIGHT_EVENT_CAP = 256', (NATIVE / 'highlight_moments.h').read_text())
        self.assertIn('HIGHLIGHT_FILE_MAX = 4 * 1024 * 1024', self.highlight)
        self.assertIn('buffer->count < HIGHLIGHT_EVENT_CAP', self.highlight)
        self.assertIn('if (local.finalized)', self.highlight)
        self.assertIn('if (dt < 120)', self.highlight)
        self.assertIn('if (selected == 0) return;', self.highlight)
        self.assertIn('fsync(fileno(out))', self.highlight)
        self.assertIn('if (won)', self.highlight)
        self.assertIn('MAX_BYTES = 4 * 1024 * 1024', self.reader)
        self.assertIn('matchId.equals(match.id)', self.reader)
        self.assertIn('moment.map.equals(match.map)', self.reader)
        self.assertIn('if (bytes[i] ==', self.reader)

    def test_ui_explicitly_says_these_are_not_video_replays(self):
        self.assertIn('MATCH HIGHLIGHTS', self.profile)
        self.assertIn('VIEW MOMENT TIMELINE', self.profile)
        self.assertIn('showMomentTimeline(activity', self.profile)
        self.assertIn('no video or', self.profile)
        self.assertNotIn('MediaCodec', self.profile)
        self.assertNotIn('MediaProjection', self.profile)
        self.assertNotIn('MediaRecorder', self.profile)

    def test_offline_source_files_not_committed_to_app(self):
        self.assertTrue((R / "tools/lan/XboxMapValidationCli.java").exists())
        self.assertTrue((JAVA / "SidewinderRepair.java").exists())
        self.assertFalse((R / "port/android/app/src/main/assets/maps/b40.map").exists())
        self.assertFalse((R / "port/android/app/src/main/assets/maps/c10.map").exists())


if __name__ == "__main__":
    unittest.main()
