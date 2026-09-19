#!/usr/bin/env bash
# Finder opens executable .command files in Terminal on macOS.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec python3 "$script_dir/Run-Scripts.py"
