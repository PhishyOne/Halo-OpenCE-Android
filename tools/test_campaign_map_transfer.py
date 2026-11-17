"""v35 fixed-ID original Xbox map audit and transfer source safeguards."""
import unittest
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/"port/android/app/src/main/java/com/halo/decomp"
REPAIR=(JAVA/"SidewinderRepair.java").read_text()
TRANSFER=(JAVA/"CampaignMapTransfer.java").read_text()
LAUNCHER=(JAVA/"LauncherActivity.java").read_text()
DIAG=(JAVA/"HaloDiagnostics.java").read_text()
NET=(ROOT/"port/linux/NETCODE.md").read_text()
VERSION=(ROOT/"port/linux/include/halo_port_limits.h").read_text()

class CampaignMapTransferIntegration(unittest.TestCase):
    def test_stock_campaign_allowlist_and_internal_identity(self):
        expected=("a10.map","a30.map","a50.map","b30.map","b40.map",
                  "c10.map","c20.map","c40.map","d20.map","d40.map")
        for name in expected:
            self.assertIn('"'+name+'"',REPAIR)
        self.assertIn('throw new IOException("This map cannot be repaired',REPAIR)
        self.assertIn('String expectedScenario = scenarioFor(targetMap)',REPAIR)
        self.assertIn('expectedScenario.equalsIgnoreCase(name)',REPAIR)
        self.assertIn('decodeCompressed(input, expectedSize - HEADER_BYTES',REPAIR)
        self.assertIn('validateTagHeader(tagHeader, title)',REPAIR)
        self.assertIn('moveInSameDirectory(target, previous)',REPAIR)
        self.assertIn('moveInSameDirectory(staged, target)',REPAIR)
        self.assertIn('restorePrevious(File dataRoot, String targetMap)',REPAIR)

    def test_saf_transfer_is_explicit_and_keeps_saves(self):
        for phrase in (
            'PICK_STOCK_MAP_IMPORT = 10',
            'PICK_STOCK_MAP_EXPORT = 11',
            'stockCampaignAuditButton.setText("CHECK ALL CAMPAIGN MAPS")',
            'stockCampaignImportButton.setText("IMPORT VERIFIED XBOX MAP")',
            'stockCampaignExportButton.setText("EXPORT SELECTED XBOX MAP")',
            'Intent.ACTION_CREATE_DOCUMENT',
            'Intent.ACTION_OPEN_DOCUMENT',
            'selectedStockCampaignMap()',
            'pendingStockMapTransfer',
            'onSaveInstanceState(Bundle state)',
            'runMapRepair("Checking the ten original campaign maps..."',
            'CampaignMapTransfer.audit(dataRoot,',
            'SidewinderRepair.install(input, dataRoot, filename)',
            'CampaignMapTransfer.export(dataRoot, filename, out)',
            'getContentResolver().openOutputStream(document, "w")',
        ):
            self.assertIn(phrase,LAUNCHER,phrase)
        for phrase in ('static String audit(File root, Progress progress)',
                       'SidewinderRepair.validate(file, name)',
                       'Files.isSymbolicLink(file.toPath())',
                       'Files.isSymbolicLink(maps.toPath())',
                       'SHA-256','destination.write(chunk, 0, amount)',
                       'MAX_BYTES = 256L * 1024 * 1024',
                       'This checks file identity, zlib stream'):
            self.assertIn(phrase,TRANSFER,phrase)
        self.assertNotIn('new File(dataRoot, filename)',LAUNCHER)
        self.assertNotIn('new File(dataRoot, name)',TRANSFER)
        self.assertNotIn('save/u',TRANSFER)
        self.assertNotIn('save/z',TRANSFER)
        self.assertNotIn('removeRecursively(',TRANSFER)
        self.assertNotIn('System.exit',TRANSFER)

    def test_separate_ce_map_download_from_repaired_stock_maps(self):
        self.assertIn('Custom Edition',NET)
        self.assertIn('custom_maps',NET)
        self.assertIn('network.map_download',NET)
        self.assertIn('The separate\n * Custom Edition map-sharing protocol',TRANSFER)
        self.assertIn('HALO_PORT_NETWORK_VERSION 52',VERSION)
        self.assertIn('starting precaching of map',DIAG)
        self.assertIn('no cache file can hold map',DIAG)
        self.assertIn('couldn.t find map',DIAG)
        self.assertIn('CHECK ALL CAMPAIGN MAPS',LAUNCHER)

if __name__=="__main__":
    unittest.main()
