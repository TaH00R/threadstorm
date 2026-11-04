#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
command -v javac >/dev/null 2>&1 || { echo 'Install a JDK 17+ and add javac to PATH.' >&2; exit 1; }
echo "[ThreadStorm] Compiling with $(javac -version 2>&1)"
mkdir -p out
javac --release 17 -encoding UTF-8 -d out Main.java core/*.java task/*.java metrics/*.java cli/*.java benchmarks/*.java tests/*.java
echo '[ThreadStorm] Build successful.'
