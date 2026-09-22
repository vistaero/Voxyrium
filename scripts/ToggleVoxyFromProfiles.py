#!/usr/bin/env python3
"""Toggle Voxy jars in every Voxy test profile.

Each run toggles Voxy between ``file.jar`` and ``file.jar.disabled``.
Detection is based on the Fabric mod id in fabric.mod.json, not on the jar
filename. Use --dry-run to list changes without renaming files.
"""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path


def default_profiles_directory() -> Path:
    if sys.platform == "darwin":
        minecraft = Path.home() / "Library/Application Support/minecraft"
    elif sys.platform == "win32":
        minecraft = Path.home() / "AppData/Roaming/.minecraft"
    else:
        minecraft = Path.home() / ".minecraft"
    return minecraft / "voxy-test-profiles"


def jar_mod_id(path: Path) -> str | None:
    """Read the Fabric mod id from a jar, returning None for non-Fabric jars."""
    try:
        with zipfile.ZipFile(path) as archive:
            raw = archive.read("fabric.mod.json")
        metadata = json.loads(raw.decode("utf-8"))
    except (KeyError, OSError, json.JSONDecodeError, UnicodeDecodeError, zipfile.BadZipFile):
        return None
    mod_id = metadata.get("id")
    return mod_id if isinstance(mod_id, str) else None


def find_voxy_jars(profiles_directory: Path) -> list[Path]:
    if not profiles_directory.is_dir():
        raise FileNotFoundError(f"Profiles directory not found: {profiles_directory}")

    matches = []
    for profile in sorted(path for path in profiles_directory.iterdir() if path.is_dir()):
        mods = profile / "mods"
        if not mods.is_dir():
            continue
        jars = list(mods.glob("*.jar")) + list(mods.glob("*.jar.disabled"))
        for jar in sorted(jars):
            if jar_mod_id(jar) == "voxy":
                matches.append(jar)
    return matches


def toggled_path(path: Path) -> Path:
    if path.name.endswith(".jar.disabled"):
        return path.with_name(path.name.removesuffix(".disabled"))
    return path.with_name(path.name + ".disabled")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--profiles-directory",
        type=Path,
        default=default_profiles_directory(),
        help="Root containing the profile directories (default: Minecraft/voxy-test-profiles).",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="List the toggle operations without renaming files.",
    )
    args = parser.parse_args()
    profiles_directory = args.profiles_directory.expanduser().resolve()

    try:
        jars = find_voxy_jars(profiles_directory)
    except FileNotFoundError as error:
        parser.error(str(error))

    targets = [(jar, toggled_path(jar)) for jar in jars]
    collisions = [target for _, target in targets if target.exists()]
    if collisions:
        for target in collisions:
            print(f"Cannot toggle; target already exists: {target}", file=sys.stderr)
        return 1

    action = "Would toggle" if args.dry_run else "Toggling"
    if not jars:
        print(f"No Voxy jars found under {profiles_directory}")
        return 0

    for jar, target in targets:
        print(f"{action}: {jar} -> {target}")

    if not args.dry_run:
        for jar, target in targets:
            jar.rename(target)
        print(f"Toggled {len(jars)} Voxy jar(s).")
    else:
        print(f"Found {len(jars)} Voxy jar(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
