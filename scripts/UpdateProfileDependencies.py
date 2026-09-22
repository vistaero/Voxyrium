#!/usr/bin/env python3
"""Update the runtime mods and shaders for the Voxy test profiles."""
from __future__ import annotations

import argparse
import importlib.util
import os
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
    compat = compat_module()
    minecraft = compat.default_minecraft_directory()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft-directory", type=Path, default=minecraft)
    parser.add_argument("--profiles-directory", type=Path, default=minecraft / "voxy-test-profiles")
    parser.add_argument("--repository-root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--output-directory", type=Path)
    parser.add_argument("--versions", nargs="+")
    parser.add_argument("--branches", nargs="+")
    parser.add_argument("--no-interactive-menu", action="store_true")
    return parser.parse_args(argv)


def run_dependencies(args, matrix, output_directory, updater, failures):
    compat = compat_module()
    updater.update_selected(matrix, args.profiles_directory)
    failures.extend(updater.failures)
    return 0


def main(argv=None):
    compat = compat_module()
    args = parse_args(argv)
    matrix = compat.select_matrix(args)
    args.repository_root = args.repository_root.expanduser().resolve()
    args.minecraft_directory = args.minecraft_directory.expanduser().resolve()
    args.profiles_directory = args.profiles_directory.expanduser().resolve()
    output_directory = (args.output_directory or args.repository_root / "compatibility-builds").expanduser().resolve()
    output_directory.mkdir(parents=True, exist_ok=True)
    updater = compat.RuntimeUpdater(output_directory)
    failures = []
    code = run_dependencies(args, matrix, output_directory, updater, failures)
    if failures:
        print("Incomplete operations:\n - " + "\n - ".join(failures), file=sys.stderr)
        return 1
    return code


if __name__ == "__main__":
    raise SystemExit(main())
