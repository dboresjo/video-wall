#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Dan Boresjö
# Builds video-wall.jar (needs JDK 21+).
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build
javac --release 21 -Xlint:all -d build/classes $(find src -name '*.java')
jar --create --file video-wall.jar --main-class videowall.Main -C build/classes .
echo "built $(pwd)/video-wall.jar"
