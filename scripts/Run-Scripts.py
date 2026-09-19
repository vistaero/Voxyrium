#!/usr/bin/env python3
"""Cross-platform interactive launcher for repository Python scripts."""
from pathlib import Path
import os
import subprocess
import sys


def clear_screen():
    os.system("cls" if os.name == "nt" else "clear")


def main():
    script_directory = Path(__file__).resolve().parent
    repository_root = script_directory.parent
    while True:
        clear_screen()
        print("========================================")
        print("         Compatibility Scripts")
        print("========================================\n")
        scripts = sorted(path for path in script_directory.glob("[A-Z]*.py") if path.name != Path(__file__).name)
        if not scripts:
            print("No Python scripts found in this folder.")
            return 1
        for index, script in enumerate(scripts, 1):
            print(f"[{index}] {script.name}")
        print("\n[Q] Quit\n")
        choice = input("Select a script: ").strip()
        if choice.lower() == "q":
            return 0
        if not choice.isdigit() or not 1 <= int(choice) <= len(scripts):
            input("\nInvalid selection. Press Enter to continue...")
            continue
        selected = scripts[int(choice) - 1]
        print(f"\nRunning {selected.name}...\n")
        result = subprocess.run([sys.executable, str(selected)], cwd=repository_root).returncode
        if result:
            print(f"\nThe script ended with exit code {result}.")
        input("Press Enter to return to the menu...")


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (EOFError, KeyboardInterrupt):
        print()
        raise SystemExit(0)
