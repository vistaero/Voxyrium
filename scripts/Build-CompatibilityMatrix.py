#!/usr/bin/env python3
"""Build the Voxy compatibility matrix and create Minecraft test profiles."""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.parse
import urllib.request
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path

if os.name == "nt":
    import msvcrt
else:
    import select
    import termios
    import tty

USER_AGENT = "vistaero-Voxyrium-compatibility-script/1.0"
MATRIX = [
    {"branch": "mc_26.3", "expected": "26.3", "versions": ["26.3"], "tasks": []},
    {"branch": "dev", "expected": "26.2", "versions": ["26.2"], "tasks": []},
    {"branch": "mc_26.1", "expected": "26.1.2", "versions": ["26.1.2"], "tasks": []},
    {"branch": "mc_26.1.1", "expected": "26.1.1", "versions": ["26.1.1"], "tasks": []},
    *[{"branch": "mc_1.21-1.21.11", "expected": "1.21.11", "build_version": version,
       "java": 21, "key": f"mc_1.21-1.21.11__{version}", "versions": [version], "tasks": []}
      for version in ["1.21"] + [f"1.21.{patch}" for patch in range(1, 12)]],
    {"branch": "mc_1.20-1.20.6", "expected": "1.20.2", "build_version": "1.20.6", "java": 21, "key": "mc_1.20-1.20.6__1.20.6", "versions": ["1.20.6"], "tasks": []},
    {"branch": "mc_1.20-1.20.6", "expected": "1.20.2", "build_version": "1.20.4", "java": 17, "key": "mc_1.20-1.20.6__1.20.4", "versions": ["1.20.4"], "tasks": []},
    {"branch": "mc_1.20-1.20.6", "expected": "1.20.2", "build_version": "1.20.2", "java": 17, "key": "mc_1.20-1.20.6__1.20.2", "versions": ["1.20.2"], "tasks": []},
    {"branch": "mc_1.20-1.20.6", "expected": "1.20.2", "build_version": "1.20.1", "java": 17, "key": "mc_1.20-1.20.6__1.20.1", "versions": ["1.20.1"], "tasks": []},
    {"branch": "mc_1.20-1.20.6", "expected": "1.20.2", "build_version": "1.20", "java": 17, "key": "mc_1.20-1.20.6__1.20", "versions": ["1.20"], "tasks": []},
]


def fail(message):
    raise RuntimeError(message)


def run(arguments, cwd=None, env=None, log=None):
    arguments = list(map(str, arguments))
    print("+", " ".join(arguments), flush=True)
    subprocess.run(arguments, cwd=cwd, env=env, check=True, stdout=log,
                   stderr=subprocess.STDOUT if log is not None else None)


def output(arguments, cwd=None):
    return subprocess.check_output(list(map(str, arguments)), cwd=cwd, text=True).strip()


def download(url, destination, headers=None, overwrite=False):
    if destination.exists() and not overwrite:
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".download")
    temporary.unlink(missing_ok=True)
    # Some download mirrors, including Adoptium's API, reject urllib's
    # anonymous default user agent.  Identify this tool for every download;
    # callers can still supply additional or overriding headers.
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
    try:
        with urllib.request.urlopen(request) as response, temporary.open("wb") as target:
            shutil.copyfileobj(response, target)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)


def json_url(url, headers=None):
    request = urllib.request.Request(url, headers=headers or {})
    with urllib.request.urlopen(request) as response:
        return json.load(response)


def version_tuple(value):
    parts = [int(part) for part in value.split(".")]
    return tuple((parts + [0, 0, 0])[:3])


def unique_versions(matrix):
    return list(dict.fromkeys(version for entry in matrix for version in entry["versions"]))


def read_key():
    if os.name == "nt":
        key = msvcrt.getwch()
        if key in ("\x00", "\xe0"):
            return {"H": "UP", "P": "DOWN"}.get(msvcrt.getwch(), "")
        return key
    descriptor = sys.stdin.fileno()
    previous = termios.tcgetattr(descriptor)
    try:
        tty.setraw(descriptor)
        # Do not mix sys.stdin's text buffer with select() on its file
        # descriptor: the buffer may already contain the remaining bytes of
        # an arrow-key escape sequence, leaving select() with nothing to see.
        key = os.read(descriptor, 1).decode()
        if key == "\x1b":
            # Terminals do not all emit cursor keys identically.  In
            # particular, application-cursor mode uses ESC O A/B rather than
            # ESC [ A/B, and the bytes can arrive a little apart over a
            # terminal multiplexer or remote session.  Wait briefly for the
            # rest of an escape sequence instead of treating its initial ESC
            # byte as a complete key press.
            while len(key) < 16 and select.select([descriptor], [], [], 0.10)[0]:
                key += os.read(descriptor, 1).decode()
            if key.startswith(("\x1b[", "\x1bO")):
                if key.endswith("A"):
                    return "UP"
                if key.endswith("B"):
                    return "DOWN"
        return key
    finally:
        termios.tcsetattr(descriptor, termios.TCSADRAIN, previous)


def interactive_version_selection(matrix):
    versions = sorted(unique_versions(matrix), key=version_tuple, reverse=True)
    if not sys.stdin.isatty() or not sys.stdout.isatty():
        fail("Interactive selection requires a terminal. Use --versions or --no-interactive-menu.")
    selected = [False] * len(versions)
    focused = 0
    while True:
        os.system("cls" if os.name == "nt" else "clear")
        print("Minecraft compatibility builds")
        print("Use the arrow keys to move, Space to select/deselect, and Enter to continue. A selects all; Q cancels.\n")
        for index, version in enumerate(versions):
            print(f"{'>' if index == focused else ' '} [{'x' if selected[index] else ' '}] {version}")
        print("Selected:", ", ".join(version for index, version in enumerate(versions) if selected[index]), flush=True)
        key = read_key()
        if key == "UP":
            focused = (focused - 1) % len(versions)
        elif key == "DOWN":
            focused = (focused + 1) % len(versions)
        elif key == " ":
            selected[focused] = not selected[focused]
        elif key in ("\r", "\n") and any(selected):
            return [version for index, version in enumerate(versions) if selected[index]]
        elif key.lower() == "a":
            return versions
        elif key.lower() == "q":
            print("\nCancelled.")
            raise SystemExit(0)


def default_minecraft_directory():
    if os.name == "nt":
        return Path(os.environ.get("APPDATA", Path.home() / "AppData/Roaming")) / ".minecraft"
    if sys.platform == "darwin":
        return Path.home() / "Library/Application Support/minecraft"
    return Path.home() / ".minecraft"


def parse_args():
    minecraft = default_minecraft_directory()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--action", choices=("profiles", "dependencies", "jdks", "compile"))
    parser.add_argument("--jdk-versions", type=int, nargs="+")
    parser.add_argument("--allow-build-downloads", action="store_true",
                        help="Allow dependency/toolchain downloads during command-line builds; compilation is offline by default.")
    parser.add_argument("--java8-home")
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--output-directory", type=Path)
    parser.add_argument("--minecraft-directory", type=Path, default=minecraft)
    parser.add_argument("--profiles-directory", type=Path, default=minecraft / "voxy-test-profiles")
    parser.add_argument("--worktree-base-directory", type=Path, default=Path(tempfile.gettempdir()) / "voxy-compat-worktrees")
    parser.add_argument("--branches", nargs="+")
    parser.add_argument("--versions", nargs="+")
    parser.add_argument("--java17-home")
    parser.add_argument("--java21-home")
    parser.add_argument("--java25-home")
    parser.add_argument("--build-workers", type=int, default=os.cpu_count() or 1,
                        help="Maximum compatibility builds to run concurrently (default: one per logical CPU).")
    parser.add_argument("--gradle-workers", type=int,
                        help="Maximum Gradle workers per build (default: share logical CPUs among concurrent builds).")
    parser.add_argument("--fabric-installer-version", default="1.1.0")
    for name in ("skip-fabric-install", "skip-runtime-mods", "skip-profile-creation", "profiles-only", "reuse-existing-artifacts", "no-interactive-menu", "keep-worktrees", "continue-on-build-failure"):
        parser.add_argument("--" + name, action="store_true")
    args = parser.parse_args()
    if args.build_workers < 1 or (args.gradle_workers is not None and args.gradle_workers < 1):
        parser.error("Worker counts must be positive.")
    if args.jdk_versions and any(major < 1 for major in args.jdk_versions):
        parser.error("JDK versions must be positive.")
    return args


def select_action(args):
    if args.action:
        return args.action
    if args.profiles_only:
        return "profiles"
    if args.no_interactive_menu or args.versions or args.branches:
        return "compile"
    if not sys.stdin.isatty():
        fail("Use --action with --versions or --no-interactive-menu outside a terminal.")
    choices = ("profiles", "dependencies", "jdks", "compile")
    while True:
        print("\n1. Create/update profiles")
        print("2. Update profile dependencies")
        print("3. Install JDK versions")
        print("4. Compile versions (offline)")
        choice = input("\nSelect an action (1-4, Q to quit): ").strip().lower()
        if choice == "q":
            raise SystemExit(0)
        if choice in ("1", "2", "3", "4"):
            return choices[int(choice) - 1]
        print("Invalid selection.")


def select_matrix(args):
    if args.branches and args.versions:
        fail("--branches and --versions cannot be used together.")
    if not args.no_interactive_menu and not args.branches and not args.versions:
        args.versions = interactive_version_selection(MATRIX)
    matrix = [dict(entry, versions=list(entry["versions"])) for entry in MATRIX]
    if args.branches:
        unknown = set(args.branches) - {entry["branch"] for entry in matrix}
        if unknown:
            fail("Unknown compatibility branches: " + ", ".join(sorted(unknown)))
        matrix = [entry for entry in matrix if entry["branch"] in args.branches]
    if args.versions:
        unknown = set(args.versions) - set(unique_versions(matrix))
        if unknown:
            fail("Unknown Minecraft versions: " + ", ".join(sorted(unknown, key=version_tuple)))
        for entry in matrix:
            entry["versions"] = [version for version in entry["versions"] if version in args.versions]
        matrix = [entry for entry in matrix if entry["versions"]]
    return matrix


def fabric_manifest(jar):
    try:
        with zipfile.ZipFile(jar) as archive:
            return json.loads(archive.read("fabric.mod.json"))
    except (KeyError, OSError, zipfile.BadZipFile, json.JSONDecodeError):
        return None


def jar_mod_id(jar):
    manifest = fabric_manifest(jar)
    return manifest.get("id") if manifest else None


def java_executable(java_home):
    return Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")


def java_major(java):
    try:
        process = subprocess.run([str(java), "-version"], text=True, capture_output=True, check=True)
    except (OSError, subprocess.CalledProcessError):
        return None
    match = re.search(r'version "(?:1\.)?(\d+)', process.stdout + "\n" + process.stderr)
    return int(match.group(1)) if match else None


def find_java_home(major):
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]))
    command = shutil.which("java")
    if command:
        candidates.append(Path(command).resolve().parent.parent)
    if sys.platform == "darwin":
        try:
            candidates.insert(0, Path(output(["/usr/libexec/java_home", "-v", str(major)])))
        except (OSError, subprocess.CalledProcessError):
            pass
    elif os.name == "nt":
        roots = [Path(os.environ.get("ProgramFiles", "C:/Program Files")) / name for name in ("Eclipse Adoptium", "Java", "Microsoft", "BellSoft")]
        roots.append(Path.home() / ".gradle/jdks")
        for root in roots:
            if root.exists():
                candidates.extend(java.parent.parent for java in root.glob("**/bin/java.exe"))
    for candidate in dict.fromkeys(candidates):
        if java_major(java_executable(candidate)) == major:
            return candidate
    return None


def get_java_home(major, configured, toolchains, offline=False):
    if configured:
        configured = Path(configured).expanduser().resolve()
        if java_major(java_executable(configured)) != major:
            fail(f"Configured Java home '{configured}' is not JDK {major}.")
        return configured
    installed = find_java_home(major)
    if installed:
        return installed
    target = toolchains / f"jdk-{major}"
    java_name = "java.exe" if os.name == "nt" else "java"
    for java in target.glob(f"**/bin/{java_name}"):
        if java_major(java) == major:
            return java.parent.parent
    if offline:
        fail(f"JDK {major} is not installed. Run 'Install JDK versions' first.")
    architecture = "aarch64" if platform.machine().lower() in ("arm64", "aarch64") else "x64"
    operating_system = "windows" if os.name == "nt" else ("mac" if sys.platform == "darwin" else "linux")
    extension = ".zip" if os.name == "nt" else ".tar.gz"
    archive = toolchains / f"jdk-{major}{extension}"
    print(f"Downloading portable JDK {major} ({operating_system}/{architecture})...")
    download(f"https://api.adoptium.net/v3/binary/latest/{major}/ga/{operating_system}/{architecture}/jdk/hotspot/normal/eclipse?project=jdk", archive)
    target.mkdir(parents=True, exist_ok=True)
    if os.name == "nt":
        with zipfile.ZipFile(archive) as source:
            source.extractall(target)
    else:
        run(["tar", "-xzf", archive, "-C", target])
    archive.unlink(missing_ok=True)
    java = next(target.glob(f"**/bin/{java_name}"), None)
    if java is None or java_major(java) != major:
        fail(f"Downloaded JDK {major} does not contain a valid Java executable.")
    return java.parent.parent


def property_value(path, name):
    pattern = re.compile(rf"^\s*{re.escape(name)}\s*=(.*)$")
    for line in path.read_text(encoding="utf-8").splitlines():
        match = pattern.match(line)
        if match:
            return match.group(1).strip()
    fail(f"Property '{name}' was not found in {path}")


def target_java_version(build_file, branch):
    match = re.search(r"targetJavaVersion\s*=\s*(\d+)", build_file.read_text(encoding="utf-8"))
    if not match:
        fail(f"Could not determine targetJavaVersion for {branch}.")
    return int(match.group(1))


def built_jar(worktree):
    jars = sorted((jar for jar in (worktree / "build/libs").glob("*.jar") if not re.search(r"(sources|javadoc|dev|dummyprovider)", jar.name, re.I)), key=lambda item: item.stat().st_size, reverse=True)
    if len(jars) != 1:
        names = ", ".join(jar.name for jar in jars)
        fail(f"Expected exactly one distributable mod JAR in '{worktree / 'build/libs'}'; found {len(jars)}: {names}")
    return jars[0]


def mod_version(project_id, version_number):
    pattern = r"-(\d+\.\d+\.\d+)(?:[-+]|$)" if project_id == "AANobbMI" else r"^(\d+(?:\.\d+){0,2})"
    match = re.search(pattern, version_number)
    return version_tuple(match.group(1)) if match else None


def matches_constraint(project_id, version_number, constraint):
    alternatives = constraint if isinstance(constraint, list) else [constraint]
    if not alternatives or "*" in alternatives:
        return True
    candidate = mod_version(project_id, version_number)
    if candidate is None:
        return False
    for value in map(str, alternatives):
        wildcard = re.fullmatch(r"=(\d+)\.(\d+)\.\*", value)
        exact = re.fullmatch(r"=?(\d+(?:\.\d+){0,2})", value)
        minimum = re.search(r">=(\d+(?:\.\d+){0,2})", value)
        maximum = re.search(r"<=(\d+(?:\.\d+){0,2})", value)
        if value == "*" or wildcard and candidate[:2] == (int(wildcard.group(1)), int(wildcard.group(2))):
            return True
        if exact and candidate == version_tuple(exact.group(1)):
            return True
        if (minimum or maximum) and (not minimum or candidate >= version_tuple(minimum.group(1))) and (not maximum or candidate <= version_tuple(maximum.group(1))):
            return True
    return False


def modrinth_file(project_id, minecraft_version, loader="fabric", constraint="*", required_id=None, number_pattern=None):
    headers = {"User-Agent": USER_AGENT}
    if required_id:
        versions = [json_url(f"https://api.modrinth.com/v2/version/{required_id}", headers)]
        item = versions[0]
        if item.get("project_id") != project_id or item.get("status") != "listed" or minecraft_version not in item.get("game_versions", []) or loader not in item.get("loaders", []):
            fail(f"Modrinth dependency version {required_id} is not a listed {loader} version for Minecraft {minecraft_version}.")
    else:
        query = urllib.parse.urlencode({"loaders": json.dumps([loader], separators=(",", ":")), "game_versions": json.dumps([minecraft_version], separators=(",", ":")), "include_changelog": "false"})
        versions = json_url(f"https://api.modrinth.com/v2/project/{project_id}/version?{query}", headers)
    listed = [item for item in versions if item.get("status") == "listed" and item.get("files") and (not required_id or item.get("id") == required_id) and (not number_pattern or re.search(number_pattern, item.get("version_number", ""))) and matches_constraint(project_id, item.get("version_number", ""), constraint)]
    if not listed:
        fail(f"Modrinth project {project_id} has no listed {loader} version for Minecraft {minecraft_version} matching constraint {constraint!r}.")
    selected = None
    for channel in ("release", "beta", "alpha"):
        candidates = sorted((item for item in listed if item.get("version_type") == channel), key=lambda item: item.get("date_published", ""), reverse=True)
        if candidates:
            selected = candidates[0]
            break
    selected = selected or sorted(listed, key=lambda item: item.get("date_published", ""), reverse=True)[0]
    selected_file = next((item for item in selected["files"] if item.get("primary")), None)
    selected_file = selected_file or next((item for item in selected["files"] if item.get("file_type") not in ("sources-jar", "dev-jar", "javadoc-jar", "signature")), None)
    if not selected_file:
        fail(f"Modrinth version {selected['id']} has no distributable primary file.")
    return {"project_id": project_id, "version_id": selected["id"], "version_number": selected["version_number"], "version_type": selected["version_type"], "file_name": selected_file["filename"], "url": selected_file["url"], "sha512": selected_file.get("hashes", {}).get("sha512"), "dependencies": selected.get("dependencies", [])}


class RuntimeUpdater:
    def __init__(self, output_directory):
        log_directory = output_directory / "update-logs"
        log_directory.mkdir(parents=True, exist_ok=True)
        self.log_path = log_directory / f"runtime-update-{datetime.now():%Y%m%d-%H%M%S}.log"
        self.output_directory = output_directory
        self.failures = []

    def log(self, message, level="INFO"):
        line = f"[{datetime.now():%Y-%m-%d %H:%M:%S}] [{level}] {message}"
        with self.log_path.open("a", encoding="utf-8") as target:
            target.write(line + "\n")
        print(line, flush=True)

    def cached(self, item):
        cache = self.output_directory / "modrinth-cache" / item["project_id"] / item["file_name"]
        valid = cache.exists() and item["sha512"] and hashlib.sha512(cache.read_bytes()).hexdigest().lower() == item["sha512"].lower()
        if cache.exists():
            self.log(f"[{item['project_id']}] Cache file {cache.name}: {'SHA-512 matches' if valid else 'SHA-512 mismatch; redownloading'}.")
        if not valid:
            download(item["url"], cache, {"User-Agent": USER_AGENT}, overwrite=True)
            actual = hashlib.sha512(cache.read_bytes()).hexdigest()
            if not item["sha512"] or actual.lower() != item["sha512"].lower():
                cache.unlink(missing_ok=True)
                fail(f"SHA-512 verification failed for {item['file_name']}.")
            self.log(f"[{item['project_id']}] Downloaded and verified {item['file_name']}.")
        return cache

    def sync_mods(self, minecraft_version, mods, voxy_artifact=None):
        mods.mkdir(parents=True, exist_ok=True)
        manifest_path = mods / ".voxy-managed-mods.json"
        previous = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else []
        projects = {"fabric-api": ("Fabric API", "P7dR8mSH"), "sodium": ("Sodium", "AANobbMI"), "cloth-config": ("Cloth Config", "9s6osm5g"), "modmenu": ("Mod Menu", "mOgUt4GM"), "iris": ("Iris Shaders", "YL57xq9U")}
        constraints = {"fabric-api": "*", "sodium": "*", "modmenu": "*", "iris": "*"}
        if minecraft_version == "1.20.1":
            constraints["sodium"] = "=0.5.13"
        if voxy_artifact:
            manifest = fabric_manifest(voxy_artifact)
            if not manifest:
                fail(f"Could not read fabric.mod.json from {voxy_artifact}.")
            for dependency, constraint in manifest.get("depends", {}).items():
                if dependency in ("minecraft", "fabricloader", "java"):
                    continue
                if dependency not in projects:
                    fail(f"Required mod '{dependency}' has no Modrinth project mapping in the compatibility script.")
                constraints[dependency] = constraint
        resolved, entries = {}, []
        order = sorted(key for key in constraints if key not in ("sodium", "iris")) + ["iris", "sodium"]
        for dependency in order:
            name, project_id = projects[dependency]
            required_id = None
            if dependency == "sodium" and "iris" in resolved:
                requirement = next((item for item in resolved["iris"]["dependencies"] if item.get("project_id") == projects["sodium"][1] and item.get("dependency_type") == "required"), None)
                if requirement and requirement.get("version_id"):
                    iris_sodium = modrinth_file(project_id, minecraft_version, constraint="*", required_id=requirement["version_id"])
                    if matches_constraint(project_id, iris_sodium["version_number"], constraints["sodium"]):
                        required_id = requirement["version_id"]
                    else:
                        self.log(f"[{minecraft_version}] Ignored Iris Sodium recommendation {iris_sodium['version_number']}; required constraint is {constraints['sodium']}.")
                elif requirement and requirement.get("version_req"):
                    constraints["sodium"] = requirement["version_req"]
            number_pattern = r"\+" + re.escape(minecraft_version) + r"(?:$|[-+])" if dependency == "iris" else None
            item = modrinth_file(project_id, minecraft_version, constraint=constraints[dependency], required_id=required_id, number_pattern=number_pattern)
            resolved[dependency] = item
            cached = self.cached(item)
            for existing in mods.glob("*.jar"):
                if existing.name != item["file_name"] and jar_mod_id(existing) == dependency:
                    existing.unlink()
                    self.log(f"[{minecraft_version}] Removed old {name} file {existing.name}.")
            shutil.copy2(cached, mods / item["file_name"])
            entries.append({"project": name, **{key: item[key] for key in ("project_id", "version_id", "version_number", "version_type", "file_name", "sha512")}})
            print(f"  {minecraft_version}: {name} {item['version_number']}")
            self.log(f"[{minecraft_version}] {name}: {item['version_number']} ({item['file_name']}).")
        current = {item["file_name"] for item in entries}
        for old in previous:
            if old.get("file_name") and old["file_name"] not in current:
                (mods / old["file_name"]).unlink(missing_ok=True)
        manifest_path.write_text(json.dumps(entries, indent=2) + "\n", encoding="utf-8")

    def sync_shaders(self, minecraft_version, game_directory):
        shaders = game_directory / "shaderpacks"
        shaders.mkdir(parents=True, exist_ok=True)
        manifest_path = shaders / ".voxy-managed-shaders.json"
        previous = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else []
        item = modrinth_file("HVnmMxH1", minecraft_version, loader="iris")
        cached = self.cached(item)
        shutil.copy2(cached, shaders / item["file_name"])
        entry = {"project": "Complementary Shaders - Reimagined", **{key: item[key] for key in ("project_id", "version_id", "version_number", "version_type", "file_name", "sha512")}}
        for old in previous:
            if old.get("file_name") and old["file_name"] != item["file_name"]:
                (shaders / old["file_name"]).unlink(missing_ok=True)
        manifest_path.write_text(json.dumps([entry], indent=2) + "\n", encoding="utf-8")
        print(f"  {minecraft_version}: {entry['project']} {item['version_number']}")
        self.log(f"[{minecraft_version}] {entry['project']}: {item['version_number']} ({item['file_name']}).")

    def update_selected(self, matrix, profiles_directory):
        versions = unique_versions(matrix)
        self.log(f"Starting automatic runtime mod update for {len(versions)} Minecraft version(s).")
        for version in versions:
            game = profile_game_directory(profiles_directory, version)
            self.log(f"[{version}] Target directory: {game}")
            try:
                self.log(f"[{version}] Updating runtime mods, including Iris.")
                voxy_jars = [jar for jar in (game / "mods").glob("*.jar") if jar_mod_id(jar) == "voxy"]
                if len(voxy_jars) > 1:
                    fail(f"Multiple Voxy JARs found in {game / 'mods'}; keep only the version being tested.")
                self.sync_mods(version, game / "mods", voxy_jars[0] if voxy_jars else None)
            except Exception as error:
                self.log(f"[{version}] Runtime mod update failed: {error}", "ERROR")
                self.failures.append(f"Runtime mod update for Minecraft {version}: {error}")
            try:
                self.log(f"[{version}] Updating shader packs.")
                self.sync_shaders(version, game)
                self.log(f"[{version}] Update completed.")
            except Exception as error:
                self.log(f"[{version}] Shader pack update failed: {error}", "ERROR")
                self.failures.append(f"Shader pack update for Minecraft {version}: {error}")
        level = "WARN" if self.failures else "INFO"
        message = f"Runtime update finished with {len(self.failures)} error(s)." if self.failures else "Runtime update finished successfully."
        self.log(message, level)


def profile_game_directory(profiles_directory, version):
    return profiles_directory.expanduser() / f"voxy-test-{re.sub(r'[^A-Za-z0-9._-]', '_', version)}"


def remove_worktree(root, worktree, allowed_root):
    if not worktree.exists():
        return
    arguments = ["git", "-C", str(root), "-c", "core.longpaths=true", "worktree", "remove", "--force", str(worktree)]
    result = subprocess.run(arguments)
    if result.returncode and worktree.exists():
        resolved, allowed = worktree.resolve(), allowed_root.resolve()
        if allowed not in resolved.parents:
            fail(f"Refusing to clean a path outside the compatibility worktree root: {resolved}")
        shutil.rmtree(resolved)


def build_matrix(args, matrix, output_directory):
    root = args.repository_root
    toolchains = output_directory / ".toolchains"
    toolchains.mkdir(parents=True, exist_ok=True)
    worktree_root = args.worktree_base_directory.expanduser() / uuid.uuid4().hex[:8]
    worktree_root.mkdir(parents=True)
    if args.build_workers < 1:
        fail("--build-workers must be at least 1.")
    if args.gradle_workers is not None and args.gradle_workers < 1:
        fail("--gradle-workers must be at least 1.")
    artifacts, java_homes, failures = {}, {}, []
    java_homes_lock = threading.Lock()
    worktree_lock = threading.Lock()
    try:
        builds = []
        for entry in matrix:
            branch = entry["branch"]
            key = entry.get("key", branch)
            safe = re.sub(r"[^A-Za-z0-9._-]", "_", key)
            if subprocess.run(["git", "-C", str(root), "show-ref", "--verify", "--quiet", f"refs/heads/{branch}"]).returncode:
                message = f"{branch}: local branch does not exist"
                failures.append(message)
                if not args.continue_on_build_failure:
                    fail(message)
                continue
            branch_commit = output(["git", "-C", root, "rev-parse", "--short=8", branch])
            existing = [jar for jar in output_directory.glob(f"voxy-compat-{safe}-*-{branch_commit}.jar") if jar_mod_id(jar) == "voxy"]
            if args.reuse_existing_artifacts or args.profiles_only:
                if len(existing) == 1:
                    print(f"Reusing {existing[0].name}")
                    artifacts[key] = existing[0]
                    continue
                if args.profiles_only:
                    failures.append(f"{branch}: no matching artifact exists for commit {branch_commit}")
                    continue
            builds.append((entry, key, safe))

        concurrent_builds = min(args.build_workers, len(builds))
        if concurrent_builds:
            gradle_workers = args.gradle_workers or max(1, (os.cpu_count() or 1) // concurrent_builds)
            print(f"\n=== Building {len(builds)} configuration(s) with up to {concurrent_builds} concurrent build(s); "
                  f"up to {gradle_workers} Gradle worker(s) per build ===", flush=True)

            def build_entry(item):
                entry, key, safe = item
                branch = entry["branch"]
                worktree = worktree_root / safe
                try:
                    print(f"\n=== Building {branch} ({entry.get('build_version', entry['expected'])}) ===", flush=True)
                    with worktree_lock:
                        run(["git", "-C", root, "-c", "core.longpaths=true", "worktree", "add", "--detach", worktree, branch])
                    source_version = property_value(worktree / "gradle.properties", "minecraft_version")
                    if source_version != entry["expected"]:
                        fail(f"Branch {branch} targets Minecraft {source_version}, expected {entry['expected']}. Complete the port before distributing this build.")
                    source_manifest = json.loads((worktree / "src/main/resources/fabric.mod.json").read_text(encoding="utf-8"))
                    if source_manifest.get("id") != "voxy":
                        fail(f"Branch {branch} contains mod id '{source_manifest.get('id')}', not 'voxy'. Complete the Voxy port before distributing this build.")
                    build_version = entry.get("build_version", source_version)
                    required_java = entry.get("java") or target_java_version(worktree / "build.gradle", branch)
                    with java_homes_lock:
                        if required_java not in java_homes:
                            java_homes[required_java] = get_java_home(required_java, getattr(args, f"java{required_java}_home", None), toolchains, offline=not args.allow_build_downloads)
                        java_home = java_homes[required_java]
                    environment = dict(os.environ)
                    environment.update({"JAVA_HOME": str(java_home), "PATH": str(java_home / "bin") + os.pathsep + environment.get("PATH", "")})
                    print(f"[{branch}] Using JDK {required_java} from {java_home}", flush=True)
                    gradle = worktree / ("gradlew.bat" if os.name == "nt" else "gradlew")
                    if not args.allow_build_downloads:
                        # Invoke an already installed distribution directly: the
                        # wrapper can download Gradle even when passed --offline.
                        properties = worktree / "gradle/wrapper/gradle-wrapper.properties"
                        url = property_value(properties, "distributionUrl")
                        distribution = url.rsplit("/", 1)[-1].removesuffix(".zip")
                        gradle_home = Path(environment.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
                        binaries = sorted((gradle_home / "wrapper/dists" / distribution).glob(
                            "*/gradle-*/bin/" + ("gradle.bat" if os.name == "nt" else "gradle")))
                        if not binaries:
                            fail(f"Gradle distribution {distribution} is not cached. Prepare it online before compiling offline.")
                        gradle = binaries[0]
                    if os.name != "nt" and args.allow_build_downloads:
                        gradle.chmod(gradle.stat().st_mode | 0o111)
                    arguments = [gradle, "--no-daemon", f"--max-workers={gradle_workers}"] + ([f"-Pminecraft_version={build_version}"] if "build_version" in entry else [])
                    if not args.allow_build_downloads:
                        arguments += ["--offline", "-Porg.gradle.java.installations.auto-download=false"]
                    logs = output_directory / "build-logs"
                    logs.mkdir(parents=True, exist_ok=True)
                    log_path = logs / f"{safe}-{datetime.now():%Y%m%d-%H%M%S}.log"
                    print(f"[{key}] Build log: {log_path}", flush=True)
                    with log_path.open("w", encoding="utf-8") as log:
                        if entry["tasks"]:
                            run(arguments + entry["tasks"], cwd=worktree, env=environment, log=log)
                            run(arguments + ["build"], cwd=worktree, env=environment, log=log)
                        else:
                            run(arguments + ["clean", "build"], cwd=worktree, env=environment, log=log)
                    jar = built_jar(worktree)
                    if jar_mod_id(jar) != "voxy":
                        fail(f"Built artifact '{jar.name}' contains mod id '{jar_mod_id(jar)}', not 'voxy'.")
                    commit = output(["git", "-C", worktree, "rev-parse", "--short=8", "HEAD"])
                    artifact = output_directory / f"voxy-compat-{safe}-mc{build_version}-{commit}.jar"
                    shutil.copy2(jar, artifact)
                    return key, artifact, None
                except Exception as error:
                    return key, None, f"{branch}: {error}"
                finally:
                    if worktree.exists() and not args.keep_worktrees:
                        with worktree_lock:
                            remove_worktree(root, worktree, worktree_root)

            with concurrent.futures.ThreadPoolExecutor(max_workers=concurrent_builds, thread_name_prefix="voxy-build") as executor:
                futures = [executor.submit(build_entry, item) for item in builds]
                for future in concurrent.futures.as_completed(futures):
                    key, artifact, error = future.result()
                    if artifact:
                        artifacts[key] = artifact
                    if error:
                        failures.append(error)
                        if not args.continue_on_build_failure:
                            for pending in futures:
                                pending.cancel()
                            break
            if failures and not args.continue_on_build_failure:
                fail(failures[0])
    finally:
        if not args.keep_worktrees:
            subprocess.run(["git", "-C", str(root), "worktree", "prune"])
            if worktree_root.exists() and not any(worktree_root.iterdir()):
                worktree_root.rmdir()
    return artifacts, failures


def fabric_loader_version(minecraft_version):
    versions = json_url(f"https://meta.fabricmc.net/v2/versions/loader/{minecraft_version}")
    stable = next((item for item in versions if item.get("loader", {}).get("stable")), None)
    if stable is None:
        fail(f"Fabric Meta returned no stable loader for Minecraft {minecraft_version}")
    return stable["loader"]["version"]


def install_fabric(minecraft, version, loader, installer):
    version_id = f"fabric-loader-{loader}-{version}"
    version_json = minecraft / "versions" / version_id / f"{version_id}.json"
    if not version_json.exists():
        java = shutil.which("java.exe" if os.name == "nt" else "java")
        if not java:
            fail("Java was not found for the Fabric installer.")
        run([java, "-jar", installer, "client", "-dir", minecraft, "-mcversion", version, "-loader", loader, "-noprofile"])
    if not version_json.exists():
        fail(f"Fabric installer did not create {version_json}")
    return version_id


def launcher_is_running():
    try:
        processes = output(["tasklist", "/FO", "CSV", "/NH"]) if os.name == "nt" else output(["ps", "-axo", "comm="])
    except (OSError, subprocess.CalledProcessError):
        return False
    return bool(re.search(r"(MinecraftLauncher|Minecraft\.Windows|GameLaunchHelper|Minecraft Launcher|minecraft-launcher)", processes, re.I))


def save_profiles(args, matrix, artifacts, version_ids, updater, failures):
    if launcher_is_running():
        fail("Close Minecraft Launcher before updating launcher_profiles.json.")
    minecraft = args.minecraft_directory
    profiles_path = minecraft / "launcher_profiles.json"
    if not profiles_path.exists():
        fail(f"Minecraft Launcher profile file was not found: {profiles_path}")
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    shutil.copy2(profiles_path, profiles_path.with_name(profiles_path.name + f".{timestamp}.bak"))
    launcher = json.loads(profiles_path.read_text(encoding="utf-8"))
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
                    if jar_mod_id(existing) == "voxy":
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
    temporary.write_text(json.dumps(launcher, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    json.loads(temporary.read_text(encoding="utf-8"))
    temporary.replace(profiles_path)


def main():
    args = parse_args()
    action = select_action(args)
    if args.profiles_only and args.skip_profile_creation:
        fail("--profiles-only and --skip-profile-creation cannot be used together.")
    matrix = [] if action == "jdks" else select_matrix(args)
    args.repository_root = args.repository_root.expanduser().resolve()
    args.minecraft_directory = args.minecraft_directory.expanduser().resolve()
    args.profiles_directory = args.profiles_directory.expanduser().resolve()
    if action == "compile" and not (args.repository_root / ".git").exists():
        fail(f"Repository root is not a Git working tree: {args.repository_root}")
    output_directory = (args.output_directory or args.repository_root / "compatibility-builds").expanduser().resolve()
    output_directory.mkdir(parents=True, exist_ok=True)
    updater = RuntimeUpdater(output_directory)
    failures = []
    if action == "jdks":
        majors = args.jdk_versions
        if not majors and not args.no_interactive_menu and sys.stdin.isatty():
            value = input("JDK versions to install [8 17 21 25] (space separated; additional versions accepted): ").strip()
            if value and (not all(part.isdigit() for part in value.split()) or any(int(part) < 1 for part in value.split())):
                fail("Enter positive JDK major versions, for example: 8 17 21 25 26.")
            majors = list(map(int, value.split())) if value else None
        for major in dict.fromkeys(majors or [8, 17, 21, 25]):
            try:
                home = get_java_home(major, getattr(args, f"java{major}_home", None), output_directory / ".toolchains")
                print(f"JDK {major}: {home}")
            except Exception as error:
                failures.append(f"JDK {major}: {error}")
    elif action == "dependencies":
        updater.update_selected(matrix, args.profiles_directory)
        failures.extend(updater.failures)
    elif action == "compile":
        artifacts, build_failures = build_matrix(args, matrix, output_directory)
        failures.extend(build_failures)
    elif action == "profiles":
        # Profile preparation neither builds nor updates mods/shaders.
        args.skip_runtime_mods = True
        if launcher_is_running():
            fail("Close Minecraft Launcher before updating profiles.")
        installer = output_directory / f"fabric-installer-{args.fabric_installer_version}.jar"
        if not args.skip_fabric_install:
            download(f"https://maven.fabricmc.net/net/fabricmc/fabric-installer/{args.fabric_installer_version}/{installer.name}", installer)
        version_ids = {}
        for version in sorted(unique_versions(matrix), key=version_tuple):
            loader = fabric_loader_version(version)
            version_ids[version] = f"fabric-loader-{loader}-{version}" if args.skip_fabric_install else install_fabric(args.minecraft_directory, version, loader, installer)
        save_profiles(args, matrix, {}, version_ids, updater, failures)
    print(f"\nArtifacts: {output_directory}")
    print(f"Test profiles: {args.profiles_directory}")
    print(f"Update log: {updater.log_path}")
    if failures:
        print("Incomplete operations:\n - " + "\n - ".join(failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print("\nCancelled.")
        raise SystemExit(130)
    except Exception as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(1)
