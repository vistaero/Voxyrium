import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("compat_matrix", Path(__file__).with_name("Build-CompatibilityMatrix.py"))
Build_CompatibilityMatrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(Build_CompatibilityMatrix)

dependency_spec = importlib.util.spec_from_file_location("update_dependencies", Path(__file__).with_name("UpdateProfileDependencies.py"))
UpdateProfileDependencies = importlib.util.module_from_spec(dependency_spec)
dependency_spec.loader.exec_module(UpdateProfileDependencies)


class JsonCompatibilityTests(unittest.TestCase):
    def test_dependency_overrides_are_parsed_as_exact_constraints(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "dependency-overrides.txt"
            path.write_text(
                "# Minecraft | dependency | version\n"
                "1.21.11 | sodium | mc1.21.11-0.8.12-fabric\n",
                encoding="utf-8",
            )

            self.assertEqual(
                UpdateProfileDependencies.load_dependency_overrides(path),
                {"1.21.11": {"sodium": "=mc1.21.11-0.8.12-fabric"}},
            )

    def test_dependency_override_takes_precedence_over_runtime_constraint(self):
        def item(project_id, version, file_name):
            return {
                "project_id": project_id,
                "version_id": version,
                "version_number": version,
                "version_type": "release",
                "file_name": file_name,
                "sha512": "deadbeef",
            }

        with tempfile.TemporaryDirectory() as temp_dir:
            temp_path = Path(temp_dir)
            cached = temp_path / "cached.jar"
            cached.write_bytes(b"test")
            updater = Build_CompatibilityMatrix.RuntimeUpdater(temp_path)
            updater.dependency_overrides = {
                "1.21.11": {"sodium": "=mc1.21.11-0.8.12-fabric"},
            }
            iris = item("YL57xq9U", "iris-test", "iris.jar")
            sodium = item("AANobbMI", "sodium-test", "sodium.jar")
            generic = item("generic", "generic-test", "generic.jar")

            with patch.object(Build_CompatibilityMatrix, "modrinth_file", return_value=generic), \
                    patch.object(updater, "compatible_pair", return_value=(iris, sodium)) as compatible_pair, \
                    patch.object(updater, "cached", return_value=cached), \
                    patch.object(updater, "log"):
                updater.sync_mods("1.21.11", temp_path / "mods")

            constraints = compatible_pair.call_args.args[1]
            self.assertEqual(constraints["sodium"], "=mc1.21.11-0.8.12-fabric")

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

    def test_create_update_profiles_script_exposes_profile_directory_helper(self):
        script_path = Path(__file__).with_name("CreateUpdateProfiles.py")
        spec = importlib.util.spec_from_file_location("create_update_profiles", script_path)
        self.assertIsNotNone(spec)
        self.assertIsNotNone(spec.loader)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        self.assertEqual(
            module.profile_game_directory(Path("profiles"), "1.21.1"),
            Path("profiles") / "voxy-test-1.21.1",
        )

    def test_parallel_build_uses_new_console_for_each_worker(self):
        with patch.object(Build_CompatibilityMatrix.subprocess, "Popen") as popen:
            Build_CompatibilityMatrix.launch_single_build_in_terminal({"key": "demo", "result_path": "out.json"}, cwd=".")
            self.assertTrue(popen.called)
            kwargs = popen.call_args.kwargs
            if Build_CompatibilityMatrix.os.name == "nt":
                self.assertTrue(kwargs.get("creationflags") & getattr(Build_CompatibilityMatrix.subprocess, "CREATE_NEW_CONSOLE", 0))
            else:
                self.assertTrue(kwargs.get("start_new_session"))

    def test_run_streams_output_live_to_console_and_log(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            log_path = Path(temp_dir) / "gradle.log"
            process = MagicMock()
            process.stdout = iter(["build started\n", "progress\n"])
            process.wait.return_value = 0
            with patch.object(Build_CompatibilityMatrix.subprocess, "Popen", return_value=process):
                with patch("builtins.print") as print_mock:
                    Build_CompatibilityMatrix.run(["gradle", "build"], log=log_path, live_console=True)
            self.assertTrue(process.wait.called)
            self.assertTrue(any("build started" in str(call.args[0]) for call in print_mock.call_args_list))
            self.assertTrue(log_path.exists())

    def test_dependency_and_jdk_scripts_are_available(self):
        for name in ("UpdateProfileDependencies.py", "InstallJdkVersions.py"):
            script_path = Path(__file__).with_name(name)
            spec = importlib.util.spec_from_file_location(name[:-3], script_path)
            self.assertIsNotNone(spec)
            self.assertIsNotNone(spec.loader)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            self.assertTrue(hasattr(module, "main") or hasattr(module, "run_dependencies") or hasattr(module, "run_jdks"))


if __name__ == "__main__":
    unittest.main()
