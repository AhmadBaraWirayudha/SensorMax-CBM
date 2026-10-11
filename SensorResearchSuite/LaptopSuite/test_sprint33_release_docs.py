"""Regression checks for Sprint 33 repository onboarding and safety docs.

Run from the project root:
    python -m unittest LaptopSuite.test_sprint33_release_docs -v
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[1]


class Sprint33ReleaseDocsTests(unittest.TestCase):
    def test_readme_uses_real_dashboard_path_and_dependency_file(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertTrue((ROOT / "web_dashboard.html").is_file())
        self.assertTrue((ROOT / "LaptopSuite" / "requirements.txt").is_file())
        self.assertIn("web_dashboard.html", readme)
        self.assertIn("LaptopSuite/requirements.txt", readme)
        self.assertIn("--skip-integration", readme)

    def test_readme_does_not_claim_device_acceptance_or_full_product_validation(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8").lower()
        self.assertIn("not yet a product-accepted release", readme)
        self.assertIn("physical android-device acceptance remain open", readme)
        self.assertIn("not claim validated fault diagnosis", readme)

    def test_security_doc_discloses_plaintext_unauthenticated_gateway(self):
        security = (ROOT / "SECURITY.md").read_text(encoding="utf-8").lower()
        self.assertIn("plaintext `ws://`", security)
        self.assertIn("no client authentication", security)
        self.assertIn("never expose it to the internet", security)
        self.assertIn("before remote/lan support can be considered complete", security)

    def test_gitignore_excludes_generated_sensitive_data_without_ignoring_all_csv(self):
        ignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        for pattern in (
            "sensormax_env/",
            "__pycache__/",
            "LaptopSuite/Imported_Records/",
            "LaptopSuite/test_output/",
            ".env",
            "*.pem",
            "*.key",
        ):
            self.assertIn(pattern, ignore)
        self.assertNotIn("*.csv", ignore)

    def test_gitignore_excludes_machine_specific_android_and_ide_state(self):
        ignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        for pattern in (
            "AndroidApp/local.properties",
            ".gradle/",
            "**/.gradle/",
            "**/build/",
            ".idea/workspace.xml",
            "*.apk",
            "*.dex",
            "LaptopSuite/Exported_Analysis/",
        ):
            self.assertIn(pattern, ignore)
        self.assertNotIn("*.csv", ignore)

    def test_readme_and_security_keep_unverified_items_explicit(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8").lower()
        security = (ROOT / "SECURITY.md").read_text(encoding="utf-8").lower()
        for phrase in (
            "full transitive lock",
            "clean gradle build",
            "physical android-device acceptance",
        ):
            self.assertIn(phrase, readme)
        self.assertIn("not a security certification", security)
        self.assertIn("no client authentication", security)

    def test_no_license_claim_is_made(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn("No project `LICENSE` file was present", readme)
        self.assertFalse((ROOT / "LICENSE").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
