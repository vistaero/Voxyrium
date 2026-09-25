"""Record the newest mutually compatible Fabric Iris/Sodium pair for Minecraft 1.21.x.

Usage: python scripts/Find-Latest-Iris-Sodium.py [--output PATH]
Modrinth's game-version tags and both JAR manifests decide compatibility; this
does not establish that a historical Voxy source snapshot can run with the pair.
"""

import argparse
from datetime import datetime, timezone
import importlib.util
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parent.parent
VERSIONS = ["1.21", *[f"1.21.{patch}" for patch in range(1, 12)]]


def load_matrix():
    sys.dont_write_bytecode = True
    spec = importlib.util.spec_from_file_location("voxy_compat_matrix", Path(__file__).with_name("Build-CompatibilityMatrix.py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def find_pair(matrix, updater, game_version):
    iris_id, sodium_id = "YL57xq9U", "AANobbMI"
    # Game-version tags are authoritative here. An artifact named mc1.21.1 can
    # be explicitly listed for 1.21, so do not filter on its filename.
    iris_versions = matrix.modrinth_candidates(iris_id, game_version)
    sodium_versions = matrix.modrinth_candidates(sodium_id, game_version)
    manifests = {}

    def manifest(item):
        key = item["version_id"]
        if key not in manifests:
            manifests[key] = updater.cached_manifest(item)
        return manifests[key]

    for iris in iris_versions:
        iris_dependency = updater.modrinth_requirement(iris, sodium_id)
        for sodium in sodium_versions:
            sodium_dependency = updater.modrinth_requirement(sodium, iris_id)
            incompatible = any(
                dependency["dependency_type"] == "incompatible" and
                dependency.get("project_id") == project_id and
                ((dependency.get("version_req") and matrix.matches_constraint(project_id, version["version_number"], dependency["version_req"])) or
                 (not dependency.get("version_req") and dependency.get("version_id") == version["version_id"]))
                for source, project_id, version in ((iris, sodium_id, sodium), (sodium, iris_id, iris))
                for dependency in source.get("dependencies", []))
            if incompatible:
                continue
            if any(dependency and dependency.get("version_req") and
                   not matrix.matches_constraint(project_id, version["version_number"], dependency["version_req"])
                   for dependency, project_id, version in (
                       (iris_dependency, sodium_id, sodium),
                       (sodium_dependency, iris_id, iris))):
                continue
            iris_manifest, sodium_manifest = manifest(iris), manifest(sodium)
            if any(requirement and not matrix.matches_constraint(project_id, version["version_number"], requirement)
                   for requirement, project_id, version in (
                       (updater.manifest_requirement(iris_manifest, "sodium"), sodium_id, sodium),
                       (updater.manifest_requirement(sodium_manifest, "iris"), iris_id, iris))):
                continue
            if any(breaks and matrix.matches_constraint(project_id, version["version_number"], breaks)
                   for breaks, project_id, version in (
                       (updater.manifest_breaks(iris_manifest, "sodium"), sodium_id, sodium),
                       (updater.manifest_breaks(sodium_manifest, "iris"), iris_id, iris))):
                continue
            return iris, sodium
    raise RuntimeError(f"No compatible Iris/Sodium pair found for {game_version}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "latest-iris-sodium-1.21.txt")
    args = parser.parse_args()
    matrix = load_matrix()
    updater = matrix.RuntimeUpdater(ROOT / "compatibility-builds")
    lines = [f"Modrinth Fabric Iris/Sodium pairs, checked {datetime.now(timezone.utc):%Y-%m-%d %H:%M UTC}",
             "Selection: newest published Iris with the newest compatible Sodium for each exact Minecraft tag.",
             "Metadata and both mod manifests checked; Voxy compilation and in-game compatibility are NOT implied.", ""]
    pairs = []
    for game_version in VERSIONS:
        iris, sodium = find_pair(matrix, updater, game_version)
        lines.append(f"{game_version} | Iris {iris['version_number']} [{iris['version_id']}] | Sodium {sodium['version_number']} [{sodium['version_id']}]")
        pairs.append((game_version, iris["version_number"], sodium["version_number"]))

    headers = ("Minecraft", "Iris", "Sodium")
    widths = [max(len(value) for value in column) for column in zip(headers, *pairs)]
    separator = "-+-".join("-" * width for width in widths)
    print("\nFound compatible Iris/Sodium pairs:")
    print(" | ".join(f"{header:<{width}}" for header, width in zip(headers, widths)))
    print(separator)
    for pair in pairs:
        print(" | ".join(f"{value:<{width}}" for value, width in zip(pair, widths)), flush=True)
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\nFound {len(VERSIONS)} compatible pairs.")
    print(f"Saved: {args.output}")


if __name__ == "__main__":
    main()
