#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Dan Boresjö
# Runs video-wall, building it first if needed. Arguments are passed through (see --help).
set -euo pipefail
dir="$(cd "$(dirname "$0")" && pwd)"
if [[ ! -f "$dir/video-wall.jar" ]] || [[ -n "$(find "$dir/src" -newer "$dir/video-wall.jar" -name '*.java' | head -1)" ]]; then
  "$dir/build.sh" >&2
fi
# uiScale=1: work in real device pixels so images stay sharp on HiDPI screens.
exec java -Dsun.java2d.uiScale=1 -jar "$dir/video-wall.jar" "$@"
