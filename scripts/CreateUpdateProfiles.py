#!/usr/bin/env python3
"""Create and update the Voxy Minecraft launcher test profiles."""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


def compat_module():
    script_path = Path(__file__).resolve().with_name("Build-CompatibilityMatrix.py")
    spec = importlib.util.spec_from_file_location("voxy_compat_matrix", script_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load compatibility script from {script_path}.")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def profile_game_directory(profiles_directory, version):
    compat = compat_module()
    return profiles_directory.expanduser() / f"voxy-test-{re.sub(r'[^A-Za-z0-9._-]', '_', version)}"


def launcher_is_running():
    compat = compat_module()
    try:
        processes = compat.output(["tasklist", "/FO", "CSV", "/NH"]) if os.name == "nt" else compat.output(["ps", "-axo", "comm="])
    except (OSError, subprocess.CalledProcessError):
        return False
    return bool(re.search(r"(MinecraftLauncher|Minecraft\.Windows|GameLaunchHelper|Minecraft Launcher|minecraft-launcher)", processes, re.I))


def fabric_loader_version(minecraft_version):
    compat = compat_module()
    versions = compat.json_url(f"https://meta.fabricmc.net/v2/versions/loader/{minecraft_version}")
    stable = next((item for item in versions if item.get("loader", {}).get("stable")), None)
    if stable is None:
        compat.fail(f"Fabric Meta returned no stable loader for Minecraft {minecraft_version}")
    return stable["loader"]["version"]


def install_fabric(minecraft, version, loader, installer):
    compat = compat_module()
    version_id = f"fabric-loader-{loader}-{version}"
    version_json = minecraft / "versions" / version_id / f"{version_id}.json"
    if not version_json.exists():
        java = shutil.which("java.exe" if os.name == "nt" else "java")
        if not java:
            compat.fail("Java was not found for the Fabric installer.")
        compat.run([java, "-jar", installer, "client", "-dir", minecraft, "-mcversion", version, "-loader", loader, "-noprofile"])
    if not version_json.exists():
        compat.fail(f"Fabric installer did not create {version_json}")
    return version_id


def save_profiles(args, matrix, artifacts, version_ids, updater, failures):
    compat = compat_module()
    if launcher_is_running():
        compat.fail("Close Minecraft Launcher before updating launcher_profiles.json.")
    minecraft = args.minecraft_directory
    profiles_path = minecraft / "launcher_profiles.json"
    if not profiles_path.exists():
        compat.fail(f"Minecraft Launcher profile file was not found: {profiles_path}")
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    shutil.copy2(profiles_path, profiles_path.with_name(profiles_path.name + f".{timestamp}.bak"))
    launcher = compat.read_json_file(profiles_path, {})
    if not isinstance(launcher, dict):
        launcher = {}
    profiles = launcher.setdefault("profiles", {})
    for entry in matrix:
        key = entry.get("key", entry["branch"])
        for version in entry["versions"]:
            safe = re.sub(r"[^A-Za-z0-9._-]", "_", version)
            game = profile_game_directory(args.profiles_directory, version)
            mods = game / "mods"
            mods.mkdir(parents=True, exist_ok=True)
            artifact = artifacts.get(key)
            if artifact:
                for existing in mods.glob("*.jar"):
                    if compat.jar_mod_id(existing) == "voxy":
                        existing.unlink()
                shutil.copy2(artifact, mods / artifact.name)
            if not args.skip_runtime_mods:
                try:
                    updater.sync_mods(version, mods, artifact)
                except Exception as error:
                    failures.append(f"Runtime mods for Minecraft {version}: {error}")
                try:
                    updater.sync_shaders(version, game)
                except Exception as error:
                    failures.append(f"Shader packs for Minecraft {version}: {error}")
            profile = profiles.setdefault(f"voxy-test-{safe}", {})
            profile.setdefault("created", datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"))
            profile.setdefault("icon", "Grass")
            profile.setdefault("name", f"Voxy Test {version}")
            profile.update({"gameDir": str(game), "lastVersionId": version_ids[version], "type": "custom"})
    temporary = profiles_path.with_name(profiles_path.name + ".codex-new")
    compat.write_json_file(temporary, launcher)
    compat.read_json_file(temporary)
    temporary.replace(profiles_path)


def parse_args(argv=None):
    compat = compat_module()
    minecraft = compat.default_minecraft_directory()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-directory", type=Path, default=minecraft)
    parser.add_argument("--profiles-directory", type=Path, default=minecraft / "voxy-test-profiles")
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--output-directory", type=Path)
    parser.add_argument("--versions", nargs="+")
    parser.add_argument("--branches", nargs="+")
    parser.add_argument("--fabric-installer-version", default="1.1.0")
    parser.add_argument("--skip-fabric-install", action="store_true")
    parser.add_argument("--skip-runtime-mods", action="store_true")
    parser.add_argument("--no-interactive-menu", action="store_true")
    parser.add_argument("--profiles-only", action="store_true")
    parser.add_argument("--skip-profile-creation", action="store_true")
    parser.add_argument("--java17-home")
    parser.add_argument("--java21-home")
    parser.add_argument("--java25-home")
    parser.add_argument("--build-workers", type=int, default=os.cpu_count() or 1)
    parser.add_argument("--gradle-workers", type=int)
    parser.add_argument("--allow-build-downloads", action="store_true")
    parser.add_argument("--continue-on-build-failure", action="store_true")
    parser.add_argument("--keep-worktrees", action="store_true")
    parser.add_argument("--reuse-existing-artifacts", action="store_true")
    return parser.parse_args(argv)


def run_profiles(args, matrix, output_directory, updater, failures):
    compat = compat_module()
    if getattr(args, "profiles_only", False) and getattr(args, "skip_profile_creation", False):
        compat.fail("--profiles-only and --skip-profile-creation cannot be used together.")
    if launcher_is_running():
        compat.fail("Close Minecraft Launcher before updating profiles.")
    installer = output_directory / f"fabric-installer-{args.fabric_installer_version}.jar"
    if not args.skip_fabric_install:
        compat.download(f"https://maven.fabricmc.net/net/fabricmc/fabric-installer/{args.fabric_installer_version}/{installer.name}", installer)
    version_ids = {}
    for version in sorted(compat.unique_versions(matrix), key=compat.version_tuple):
        loader = fabric_loader_version(version)
        version_ids[version] = f"fabric-loader-{loader}-{version}" if args.skip_fabric_install else install_fabric(args.minecraft_directory, version, loader, installer)
    args.skip_runtime_mods = True
    save_profiles(args, matrix, {}, version_ids, updater, failures)
    return 0


def main(argv=None):
    compat = compat_module()
    args = parse_args(argv)
    if args.profiles_only and args.skip_profile_creation:
        compat.fail("--profiles-only and --skip-profile-creation cannot be used together.")
    matrix = compat.select_matrix(args)
    args.repository_root = args.repository_root.expanduser().resolve()
    args.minecraft_directory = args.minecraft_directory.expanduser().resolve()
    args.profiles_directory = args.profiles_directory.expanduser().resolve()
    output_directory = (args.output_directory or args.repository_root / "compatibility-builds").expanduser().resolve()
    output_directory.mkdir(parents=True, exist_ok=True)
    updater = compat.RuntimeUpdater(output_directory)
    failures = []
    code = run_profiles(args, matrix, output_directory, updater, failures)
    if failures:
        print("Incomplete operations:\n - " + "\n - ".join(failures), file=sys.stderr)
        return 1
    return code


if __name__ == "__main__":
    raise SystemExit(main())
