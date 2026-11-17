"""UNSC Service Rank, original icon, and truthful Spartan Rating UI tests."""
from pathlib import Path
import hashlib
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "port/android/app/src/main/java/com/halo/decomp"
RES = ROOT / "port/android/app/src/main/res"
EXPECTED_ICON_SHA256 = "937b3876cf0da75724878158b2e604268bfdd20d2cd59c82984dddbd0a348ee8"
NAMES = [
    "Recruit", "Private", "Corporal", "Sergeant", "Staff Sergeant",
    "Lieutenant", "Captain", "Major", "Colonel", "Brigadier",
    "General", "Legend",
]
XP = [0, 1000, 3000, 6500, 12000, 20000, 32000,
      50000, 80000, 125000, 190000, 300000]


class RankArtAndRatings(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.career = (JAVA / "CareerRecords.java").read_text()
        cls.profile = (JAVA / "CareerProfile.java").read_text()
        cls.gallery = (JAVA / "RankGallery.java").read_text()
        cls.badges = (JAVA / "RankInsignia.java").read_text()

    def test_rank_naming_and_difficulty_unchanged(self):
        for title in NAMES:
            self.assertIn('"' + title + '"', self.career)
        for xp in XP:
            self.assertIn(str(xp), self.career)
        self.assertIn('SERVICE_RANK_TITLE = "UNSC SERVICE RANK"', self.career)
        self.assertIn('static int rankCount() { return RANKS.length; }', self.career)
        self.assertIn('return THRESHOLDS[index];', self.career)
        self.assertIn('return RANKS[index];', self.career)
        self.assertIn('career_record', (ROOT /
            "port/linux/game/career_record.c").read_text())

    def test_real_profile_and_gallery_share_same_artwork(self):
        self.assertIn('RankGallery.show(activity, result.rankIndex())', self.profile)
        self.assertIn('RankGallery.show(activity,', self.profile)
        self.assertIn('new RankInsignia(activity, rank)', self.gallery)
        self.assertIn('CareerRecords.rankName(rank)', self.gallery)
        self.assertIn('CareerRecords.rankThreshold(rank)', self.gallery)
        self.assertIn('CareerRecords.rankName(rank)', self.badges)
        self.assertIn('case 10:', self.badges)
        self.assertIn('default: // Legend', self.badges)
        self.assertIn('CURRENT RANK', self.gallery)
        self.assertIn('canvas.scale(scale, scale)', self.badges)

    def test_skill_rating_is_unrated_until_verified_host_results(self):
        self.assertIn('"SPARTAN RATING"', self.profile)
        self.assertGreaterEqual(self.profile.count('"UNRATED"'), 2)
        self.assertIn('Verified human matches are required', self.profile)
        self.assertIn('Bot kills and career XP do not affect skill rating.', self.profile)
        # Never infer skill from legacy aggregate self-reported career games.
        self.assertNotIn('rating = career.xp', self.profile)
        self.assertNotIn('rating = career.kills', self.profile)
        self.assertNotIn('rating = career.wins', self.profile)

    def test_bottom_explanatory_footer_removed(self):
        self.assertNotIn('Offline career history, stored only on this phone.', self.profile)
        self.assertNotIn('Local career progression only.', self.profile)

    def test_selected_helmet_icon_packaging_source(self):
        image = RES / "drawable-nodpi/halo_selected_icon.webp"
        self.assertTrue(image.is_file())
        data = image.read_bytes()
        self.assertEqual(hashlib.sha256(data).hexdigest(), EXPECTED_ICON_SHA256)
        self.assertEqual(data[:4], b"RIFF")
        self.assertEqual(data[8:12], b"WEBP")
        icon_xml = ET.parse(RES / "mipmap-anydpi-v26/ic_launcher.xml")
        a = "{http://schemas.android.com/apk/res/android}"
        tags = {tag.tag.split("}")[-1]: tag.attrib.get(a+"drawable", "")
                for tag in icon_xml.getroot()}
        self.assertEqual(tags["foreground"],
                         "@drawable/halo_launcher_foreground_v40")
        self.assertEqual(tags["monochrome"],
                         "@drawable/halo_launcher_monochrome_v40")
        inset = ET.parse(RES / "drawable/halo_launcher_foreground_v40.xml").getroot()
        self.assertEqual(inset.attrib.get(a+"drawable"), "@drawable/halo_selected_icon")
        for side in ("Left", "Right", "Top", "Bottom"):
            self.assertEqual(inset.attrib.get(a+"inset"+side), "18dp")


if __name__ == "__main__":
    unittest.main()
