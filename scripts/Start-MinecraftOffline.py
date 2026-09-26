#!/usr/bin/env python3
"""Launch the Voxy test profiles without authenticating with Minecraft."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import uuid
import zipfile
from pathlib import Path
from typing import Any, Iterable


SCRIPT_DIRECTORY = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIRECTORY.parent
MOJANG_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
PLATFORM_MAP = {"win32": "windows", "darwin": "osx", "linux": "linux"}
CURRENT_OS = PLATFORM_MAP.get(sys.platform, "linux")


def default_minecraft_directory() -> Path:
    if os.name == "nt":
        appdata = os.environ.get("APPDATA")
        if appdata:
            return Path(appdata) / ".minecraft"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "minecraft"
    return Path.home() / ".minecraft"


def property_value(value: Any, name: str, default: Any = None) -> Any:
    return value.get(name, default) if isinstance(value, dict) else default


def is_arm64() -> bool:
    return platform.machine().lower() in {"arm64", "aarch64"}


def is_current_native_classifier(classifier: str) -> bool:
    """Return whether a modern Mojang ``natives-*`` classifier is for this host."""
    if not classifier.startswith("natives-"):
        return False
    if sys.platform == "darwin":
        return classifier == ("natives-macos-arm64" if is_arm64() else "natives-macos") or (
            classifier == "natives-macos-patch" and not is_arm64()
        )
    if sys.platform == "win32":
        return classifier == ("natives-windows-arm64" if is_arm64() else "natives-windows")
    return classifier == ("natives-linux-arm64" if is_arm64() else "natives-linux")


def allowed_by_rules(rules: Iterable[Any]) -> bool:
    rules = list(rules or [])
    if not rules:
        return True

    allowed = False
    for rule in rules:
        if not isinstance(rule, dict):
            continue
        matches = True
        operating_system = rule.get("os")
        if isinstance(operating_system, dict):
            if operating_system.get("name") and operating_system["name"] != CURRENT_OS:
                matches = False
            if matches and operating_system.get("arch"):
                architecture = "amd64" if sys.maxsize > 2**32 else "x86"
                if operating_system["arch"] not in (architecture, "x86_64" if architecture == "amd64" else architecture):
                    matches = False
            if matches and operating_system.get("version"):
                if not re.search(str(operating_system["version"]), sys.getwindowsversion().platform_version if os.name == "nt" else os.uname().release):
                    matches = False
        if matches and isinstance(rule.get("features"), dict):
            # Offline profiles do not enable demo, quick-play, or custom resolution.
            if any(bool(enabled) for enabled in rule["features"].values()):
                matches = False
        if matches:
            allowed = rule.get("action") == "allow"
    return allowed


class OfflineLauncher:
    def __init__(self, args: argparse.Namespace) -> None:
        self.args = args
        self.minecraft_directory = args.minecraft_directory.expanduser().resolve()
        self.manifest: dict[str, Any] | None = None

    def download(self, uri: str, destination: Path) -> None:
        if destination.is_file():
            return
        if self.args.no_download:
            if self.args.dry_run:
                print(f"Dry run missing: {destination}", file=sys.stderr)
                return
            raise RuntimeError(f"Missing required file: {destination}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(destination.name + ".download")
        print(f"Downloading {destination.name}", file=sys.stderr)
        try:
            with urllib.request.urlopen(uri) as response, temporary.open("wb") as output:
                shutil.copyfileobj(response, output)
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)

    def version_data(self, version_id: str) -> dict[str, Any]:
        directory = self.minecraft_directory / "versions" / version_id
        json_path = directory / f"{version_id}.json"
        if not json_path.is_file():
            if self.args.no_download:
                raise RuntimeError(f"Minecraft version metadata is missing: {version_id}")
            if self.manifest is None:
                with urllib.request.urlopen(MOJANG_MANIFEST_URL) as response:
                    self.manifest = json.load(response)
            entry = next((item for item in self.manifest["versions"] if item["id"] == version_id), None)
            if entry is None:
                raise RuntimeError(f"Version '{version_id}' is not present in Mojang's manifest.")
            self.download(entry["url"], json_path)
        with json_path.open(encoding="utf-8") as source:
            return json.load(source)

    def version_chain(self, version_id: str) -> list[tuple[str, dict[str, Any]]]:
        chain: list[tuple[str, dict[str, Any]]] = []
        seen: set[str] = set()
        current: str | None = version_id
        while current:
            if current in seen:
                raise RuntimeError(f"Circular version inheritance at '{current}'.")
            seen.add(current)
            data = self.version_data(current)
            chain.insert(0, (current, data))
            current = data.get("inheritsFrom")
        return chain

    @staticmethod
    def argument_values(items: Iterable[Any]) -> list[str]:
        result: list[str] = []
        for item in items or []:
            if isinstance(item, str):
                result.append(item)
                continue
            if not isinstance(item, dict) or not allowed_by_rules(item.get("rules", [])):
                continue
            value = item.get("value", [])
            result.extend(str(part) for part in (value if isinstance(value, list) else [value]))
        return result

    @staticmethod
    def expand_variables(arguments: Iterable[str], variables: dict[str, Any]) -> list[str]:
        expanded = []
        for argument in arguments:
            value = argument
            for key, replacement in variables.items():
                value = value.replace("${" + key + "}", str(replacement))
            expanded.append(value)
        return expanded

    @staticmethod
    def offline_uuid(username: str) -> str:
        digest = bytearray(hashlib.md5(f"OfflinePlayer:{username}".encode("utf-8")).digest())
        digest[6] = (digest[6] & 0x0F) | 0x30
        digest[8] = (digest[8] & 0x3F) | 0x80
        return str(uuid.UUID(bytes=bytes(digest)))

    def java_executable(self, major: int, component: str | None) -> Path:
        candidates: list[Path] = []
        if component:
            if os.name == "nt":
                candidates.append(self.minecraft_directory / "runtime" / component / "windows-x64" / component / "bin" / "java.exe")
            elif sys.platform == "darwin":
                candidates.extend((
                    self.minecraft_directory / "runtime" / component / "mac-os" / component / "jre.bundle" / "Contents" / "Home" / "bin" / "java",
                    self.minecraft_directory / "runtime" / component / "macosx" / component / "bin" / "java",
                ))
            else:
                candidates.append(self.minecraft_directory / "runtime" / component / "linux-x86_64" / component / "bin" / "java")
        toolchains = REPOSITORY_ROOT / "compatibility-builds" / ".toolchains"
        if toolchains.is_dir():
            candidates.extend(path for path in toolchains.rglob("java.exe" if os.name == "nt" else "java") if f"jdk-{major}" in str(path))
        java = shutil.which("java")
        if java:
            candidates.append(Path(java))

        for candidate in candidates:
            if not candidate.is_file():
                continue
            try:
                process = subprocess.run([str(candidate), "-version"], capture_output=True, text=True, check=False)
            except OSError:
                continue
            version_text = process.stderr + "\n" + process.stdout
            match = re.search(r'version "(?:1\.)?(\d+)', version_text)
            if match and int(match.group(1)) == major:
                return candidate
        raise RuntimeError(f"Java {major} was not found. Run the compatibility build once so its toolchain is installed.")

    def start_profile(self, profile_id: str, profile: dict[str, Any]) -> None:
        # A malformed historical profile may contain installer output before the ID.
        version_id = final_version_id(profile.get("lastVersionId", ""))
        if not version_id:
            raise RuntimeError(f"Profile '{profile_id}' has no lastVersionId.")
        game_directory = Path(profile.get("gameDir") or self.minecraft_directory).expanduser()
        game_directory.mkdir(parents=True, exist_ok=True)
        chain = self.version_chain(version_id)
        libraries: dict[str, dict[str, Any]] = {}
        game_arguments: list[str] = []
        jvm_arguments: list[str] = []
        main_class = asset_index = asset_name = logging_argument = None
        java_major, java_component = 8, None

        for chain_id, data in chain:
            main_class = data.get("mainClass", main_class)
            if isinstance(data.get("assetIndex"), dict):
                asset_index = data["assetIndex"]
                asset_name = str(asset_index["id"])
            if data.get("assets"):
                asset_name = str(data["assets"])
            if isinstance(data.get("javaVersion"), dict):
                java_major = int(data["javaVersion"]["majorVersion"])
                java_component = data["javaVersion"].get("component")
            for library in data.get("libraries", []):
                coordinates = str(library.get("name", "")).split(":")
                key = ":".join(coordinates[:2] + ([coordinates[3]] if len(coordinates) >= 4 else [])) if len(coordinates) >= 2 else str(library.get("name"))
                libraries[key] = library
            arguments = data.get("arguments")
            if isinstance(arguments, dict):
                jvm_arguments.extend(self.argument_values(arguments.get("jvm", [])))
                game_arguments.extend(self.argument_values(arguments.get("game", [])))
            elif data.get("minecraftArguments"):
                game_arguments.extend(shlex.split(str(data["minecraftArguments"]), posix=False))
            client = property_value(property_value(data.get("logging"), "client"), "file")
            if client:
                log_file = self.minecraft_directory / "assets" / "log_configs" / str(client["id"])
                self.download(str(client["url"]), log_file)
                logging_argument = str(data["logging"]["client"]["argument"]).replace("${path}", str(log_file))
            client_download = property_value(property_value(data.get("downloads"), "client"), "url")
            if client_download:
                self.download(str(client_download), self.minecraft_directory / "versions" / chain_id / f"{chain_id}.jar")

        class_path: list[str] = []
        native_jars: list[Path] = []
        for library in libraries.values():
            if not allowed_by_rules(library.get("rules", [])):
                continue
            coordinates = str(library.get("name", "")).split(":")
            classifier_name = coordinates[3] if len(coordinates) >= 4 else ""
            is_native_classifier = classifier_name.startswith("natives-")
            # Current version metadata publishes native classifiers as ordinary,
            # OS-gated artifacts. They must remain on the classpath so LWJGL and
            # the other libraries can extract them into their configured
            # per-library directories. Only use the classifier-name fallback
            # for metadata that does not provide launcher rules.
            if (
                is_native_classifier
                and not library.get("rules")
                and not is_current_native_classifier(classifier_name)
            ):
                continue
            downloads = library.get("downloads", {})
            artifact = downloads.get("artifact") if isinstance(downloads, dict) else None
            if artifact:
                path = self.minecraft_directory / "libraries" / Path(str(artifact["path"]))
                self.download(str(artifact["url"]), path)
                class_path.append(str(path))
            elif len(coordinates) == 3:
                group, name, version = coordinates
                relative = Path(group.replace(".", "/")) / name / version / f"{name}-{version}.jar"
                path = self.minecraft_directory / "libraries" / relative
                base_url = str(library.get("url", "https://libraries.minecraft.net/")).rstrip("/")
                self.download(f"{base_url}/{relative.as_posix()}", path)
                class_path.append(str(path))
            natives = library.get("natives", {})
            if isinstance(natives, dict) and CURRENT_OS in natives and isinstance(downloads, dict):
                architecture = "64" if sys.maxsize > 2**32 else "32"
                classifier_name = str(natives[CURRENT_OS]).replace("${arch}", architecture)
                classifier = downloads.get("classifiers", {}).get(classifier_name)
                if classifier:
                    path = self.minecraft_directory / "libraries" / Path(str(classifier["path"]))
                    self.download(str(classifier["url"]), path)
                    native_jars.append(path)
        for chain_id, _ in chain:
            jar = self.minecraft_directory / "versions" / chain_id / f"{chain_id}.jar"
            if jar.is_file():
                class_path.append(str(jar))

        if not asset_index or not asset_name:
            raise RuntimeError(f"Version '{version_id}' has no asset index.")
        assets_index_path = self.minecraft_directory / "assets" / "indexes" / f"{asset_name}.json"
        self.download(str(asset_index["url"]), assets_index_path)
        with assets_index_path.open(encoding="utf-8") as source:
            assets = json.load(source)
        for asset in assets.get("objects", {}).values():
            asset_hash = str(asset["hash"])
            self.download(f"https://resources.download.minecraft.net/{asset_hash[:2]}/{asset_hash}", self.minecraft_directory / "assets" / "objects" / asset_hash[:2] / asset_hash)

        natives_directory = game_directory / ".voxy-natives" / re.sub(r"[^A-Za-z0-9._-]", "_", version_id)
        natives_directory.mkdir(parents=True, exist_ok=True)
        for native_jar in native_jars:
            with zipfile.ZipFile(native_jar) as archive:
                for entry in archive.infolist():
                    if not entry.filename or entry.filename.startswith("META-INF/") or entry.is_dir():
                        continue
                    (natives_directory / Path(entry.filename).name).write_bytes(archive.read(entry))

        variables = {
            "natives_directory": natives_directory, "launcher_name": "VoxyCompatibility", "launcher_version": "1",
            "classpath": os.pathsep.join(class_path), "classpath_separator": os.pathsep,
            "library_directory": self.minecraft_directory / "libraries", "auth_player_name": self.args.offline_username,
            "version_name": version_id, "game_directory": game_directory, "assets_root": self.minecraft_directory / "assets",
            "assets_index_name": asset_name, "auth_uuid": self.offline_uuid(self.args.offline_username), "auth_access_token": "0",
            "clientid": "", "auth_xuid": "", "user_type": "legacy", "version_type": "custom", "user_properties": "{}",
            "resolution_width": "1280", "resolution_height": "720", "game_assets": self.minecraft_directory / "assets",
        }
        expanded_jvm = self.expand_variables(jvm_arguments, variables)
        memory_argument = f"-Xmx{self.args.maximum_ram_mb}M"
        if memory_argument not in expanded_jvm:
            expanded_jvm.insert(0, memory_argument)
        if logging_argument:
            expanded_jvm.append(self.expand_variables([logging_argument], variables)[0])
        expanded_game = self.expand_variables(game_arguments, variables)
        java = self.java_executable(java_major, java_component)
        javaw = java.with_name("javaw.exe") if os.name == "nt" and java.with_name("javaw.exe").is_file() else java
        command = [str(javaw), *expanded_jvm, str(main_class), *expanded_game]
        print(f"Launching {profile.get('name', profile_id)} offline as {self.args.offline_username} (Java {java_major})")
        if self.args.dry_run:
            print(f"Dry run: command resolved ({len(command) - 1} arguments).")
            return
        launch_log_base = game_directory / ".voxy-launch"
        if sys.platform == "darwin":
            output_path = launch_log_base.with_suffix(".log")
            error_path = output_path
        else:
            output_path = launch_log_base.with_suffix(".out.log")
            error_path = launch_log_base.with_suffix(".err.log")
        with output_path.open("w", encoding="utf-8") as stdout:
            if output_path == error_path:
                process = subprocess.Popen(command, cwd=game_directory, stdout=stdout, stderr=subprocess.STDOUT)
            else:
                with error_path.open("w", encoding="utf-8") as stderr:
                    process = subprocess.Popen(command, cwd=game_directory, stdout=stdout, stderr=stderr)
            if self.args.wait:
                exit_code = process.wait()
                if exit_code:
                    error_log = error_path.read_text(encoding="utf-8", errors="replace") or "No error output was produced."
                    raise RuntimeError(f"Minecraft exited with code {exit_code}:\n{error_log}")


def version_sort_key(profile_id: str) -> tuple[int, ...]:
    return tuple(int(part) if part.isdigit() else 0 for part in re.findall(r"\d+", profile_id.removeprefix("voxy-test-")))


def final_version_id(value: Any) -> str:
    if isinstance(value, list):
        value = value[-1] if value else ""
    return str(value).splitlines()[-1] if str(value) else ""


def select_profiles(profiles: dict[str, Any]) -> list[str]:
    available = sorted((name for name in profiles if name.startswith("voxy-test-")), key=version_sort_key, reverse=False)
    if not available:
        raise RuntimeError("No Voxy test profiles were found.")
    selected: set[int] = set()
    focused = 0
    if not sys.stdin.isatty():
        choice = input("Profiles (comma-separated, or A for all): ").strip()
        if choice.lower() == "a":
            return available
        return [available[int(index) - 1] for index in choice.split(",") if index.strip().isdigit() and 1 <= int(index) <= len(available)]
    try:
        import msvcrt

        def read_key() -> str:
            key = msvcrt.getwch()
            if key in ("\x00", "\xe0"):
                return {"H": "up", "P": "down"}.get(msvcrt.getwch(), "")
            return {" ": "space", "\r": "enter", "a": "all", "A": "all", "q": "quit", "Q": "quit"}.get(key, "")
    except ImportError:

        import termios
        import tty

        def read_key() -> str:
            file_descriptor = sys.stdin.fileno()
            previous_settings = termios.tcgetattr(file_descriptor)
            try:
                tty.setcbreak(file_descriptor)
                key = sys.stdin.read(1)
                if key == "\x1b":
                    sequence = sys.stdin.read(2)
                    return {"[A": "up", "[B": "down"}.get(sequence, "")
                if key == " ":
                    return "space"
                if key in ("\r", "\n"):
                    return "enter"
                if key in ("a", "A"):
                    return "all"
                if key in ("q", "Q"):
                    return "quit"
                if key.isdigit():
                    index = int(key) - 1
                    if 0 <= index < len(available):
                        selected.symmetric_difference_update({index})
                return ""
            finally:
                termios.tcsetattr(file_descriptor, termios.TCSADRAIN, previous_settings)
    while True:
        os.system("cls" if os.name == "nt" else "clear")
        print("Available Minecraft profiles")
        print("Use arrow keys, Space to select/deselect, and Enter to launch. A selects all; Q cancels.\n")
        for index, name in enumerate(available):
            print(f"{'>' if index == focused else ' '} [{'x' if index in selected else ' '}] {profiles[name].get('name', name)}")
        print("\nMarked: " + ", ".join(available[index] for index in sorted(selected)))
        key = read_key()
        if key == "up":
            focused = (focused - 1) % len(available)
        elif key == "down":
            focused = (focused + 1) % len(available)
        elif key == "space":
            selected.symmetric_difference_update({focused})
        elif key == "all":
            return available
        elif key == "quit":
            raise SystemExit(0)
        elif key == "enter" and selected:
            return [available[index] for index in sorted(selected)]


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-version", action="append", default=[], help="Minecraft version to launch; repeatable")
    parser.add_argument("--profile-id", action="append", default=[], help="Launcher profile ID; repeatable")
    parser.add_argument("--minecraft-directory", type=Path, default=default_minecraft_directory())
    parser.add_argument("--offline-username", default=os.environ.get("USERNAME") or os.environ.get("USER") or "Player")
    parser.add_argument("--maximum-ram-mb", type=int, default=4096)
    parser.add_argument("--no-download", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--wait", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_arguments()
    launcher = OfflineLauncher(args)
    profiles_path = launcher.minecraft_directory / "launcher_profiles.json"
    with profiles_path.open(encoding="utf-8") as source:
        launcher_data = json.load(source)
    profiles = launcher_data.get("profiles", {})
    profile_ids = args.profile_id
    if not profile_ids and not args.minecraft_version:
        profile_ids = select_profiles(profiles)
    selected = []
    for profile_id, profile in profiles.items():
        last_version_id = final_version_id(profile.get("lastVersionId", ""))
        version = re.sub(r"^fabric-loader-[^-]+-", "", last_version_id)
        if profile_id in profile_ids or version in args.minecraft_version:
            selected.append((profile_id, profile))
    if not selected:
        raise RuntimeError("No matching Minecraft profiles were found.")
    for profile_id, profile in selected:
        launcher.start_profile(profile_id, profile)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KeyboardInterrupt, EOFError):
        print()
        raise SystemExit(130)
    except (OSError, RuntimeError, ValueError, json.JSONDecodeError) as error:
        print(f"Error: {error}", file=sys.stderr)
        raise SystemExit(1)
