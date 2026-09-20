import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("compat_matrix", Path(__file__).with_name("Build-CompatibilityMatrix.py"))
Build_CompatibilityMatrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(Build_CompatibilityMatrix)


class JsonCompatibilityTests(unittest.TestCase):
    def test_read_json_file_accepts_utf8_bom(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "launcher_profiles.json"
            path.write_text('\ufeff{\n  "profiles": {\n    "demo": {\n      "name": "Demo"\n    }\n  }\n}\n', encoding='utf-8')

            self.assertEqual(Build_CompatibilityMatrix.read_json_file(path)["profiles"]["demo"]["name"], "Demo")

    def test_sync_shaders_legacy_manifest_object_is_supported(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            shaders = temp_path / "shaderpacks"
            shaders.mkdir()
            manifest = shaders / ".voxy-managed-shaders.json"
            manifest.write_text('\ufeff{\n  "project": "Legacy Shader",\n  "file_name": "legacy.zip"\n}\n', encoding='utf-8')

            cached_zip = temp_path / "mod.zip"
            cached_zip.write_bytes(b"shader-pack")
            with patch.object(Build_CompatibilityMatrix, "modrinth_file", return_value={
                "project_id": "HVnmMxH1",
                "version_id": "abc",
                "version_number": "r5.9.3",
                "version_type": "release",
                "file_name": "ComplementaryReimagined_r5.9.3.zip",
                "sha512": "deadbeef",
            }), patch.object(Build_CompatibilityMatrix.RuntimeUpdater, "cached", return_value=cached_zip):
                updater = Build_CompatibilityMatrix.RuntimeUpdater(temp_path)
                with patch.object(updater, "log"):
                    updater.sync_shaders("1.21", temp_path)

            self.assertTrue((shaders / "ComplementaryReimagined_r5.9.3.zip").exists())


if __name__ == "__main__":
    unittest.main()
