#!/usr/bin/env python3
"""Install the JDK toolchains required by the Voxy compatibility builds."""
from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path


def compat_module():
    script_path = Path(__file__).resolve().with_name("Build-CompatibilityMatrix.py")
    spec = importlib.util.spec_from_file_location("voxy_compat_matrix", script_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load compatibility script from {script_path}.")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk-versions", type=int, nargs="+")
    parser.add_argument("--java8-home")
    parser.add_argument("--java17-home")
    parser.add_argument("--java21-home")
    parser.add_argument("--java25-home")
    parser.add_argument("--output-directory", type=Path)
    parser.add_argument("--no-interactive-menu", action="store_true")
    return parser.parse_args(argv)


def run_jdks(args, failures):
    compat = compat_module()
    majors = args.jdk_versions
    if not majors and not args.no_interactive_menu:
        value = input("JDK versions to install [8 17 21 25] (space separated; additional versions accepted): ").strip()
        if value and (not all(part.isdigit() for part in value.split()) or any(int(part) < 1 for part in value.split())):
            compat.fail("Enter positive JDK major versions, for example: 8 17 21 25 26.")
        majors = list(map(int, value.split())) if value else None
    for major in dict.fromkeys(majors or [8, 17, 21, 25]):
        try:
            home = compat.get_java_home(major, getattr(args, f"java{major}_home", None), args.output_directory / ".toolchains")
            print(f"JDK {major}: {home}")
        except Exception as error:
            failures.append(f"JDK {major}: {error}")
    return 0


def main(argv=None):
    compat = compat_module()
    args = parse_args(argv)
    output_directory = (args.output_directory or Path(__file__).resolve().parent.parent / "compatibility-builds").expanduser().resolve()
    output_directory.mkdir(parents=True, exist_ok=True)
    args.output_directory = output_directory
    failures = []
    code = run_jdks(args, failures)
    if failures:
        print("Incomplete operations:\n - " + "\n - ".join(failures), file=sys.stderr)
        return 1
    return code


if __name__ == "__main__":
    raise SystemExit(main())
