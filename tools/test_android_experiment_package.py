"""Ensure Android-only CI produces a separate package without changing public builds."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AndroidExperimentPackageTest(unittest.TestCase):
    def test_package_is_opt_in(self):
        gradle=(ROOT/"port/android/app/build.gradle").read_text()
        self.assertIn('System.getenv("HALO_ANDROID_EXPERIMENT_PACKAGE") == "true"',gradle)
        self.assertIn('applicationId experimentalPackage ? "com.halo.decomp.experiment" : "com.halo.decomp"',gradle)
        self.assertIn('if (experimentalPackage && (!signingKey ||',gradle)
        self.assertIn('haloAppLabel: "Halo"',gradle)
        self.assertNotIn('haloAppLabel: experimentalPackage ? "Halo Experiment"',gradle)
        self.assertIn('"com.halo.decomp.experiment.update" : "com.halo.decomp.update"',gradle)

    def test_provider_authority_matches_application_id(self):
        manifest=(ROOT/"port/android/app/src/main/AndroidManifest.xml").read_text()
        provider=(ROOT/"port/android/app/src/main/java/com/halo/decomp/UpdateProvider.java").read_text()
        self.assertIn('android:authorities="'+chr(36)+'{haloUpdateAuthority}"',manifest)
        self.assertIn('android:label="'+chr(36)+'{haloAppLabel}"',manifest)
        self.assertIn('BuildConfig.APPLICATION_ID + ".update"',provider)

    def test_ci_opt_in_only_for_scoped_build(self):
        workflow=(ROOT/".github/workflows/build.yml").read_text()
        self.assertIn("HALO_ANDROID_EXPERIMENT_PACKAGE: "+chr(36)+"{{ inputs.android_only && 'true' || 'false' }}",workflow)
        self.assertIn('HALO_ANDROID_VERSION_CODE:',workflow)
        self.assertIn('if: '+chr(36)+'{{ !inputs.android_only }}',workflow)


if __name__ == "__main__":
    unittest.main()
