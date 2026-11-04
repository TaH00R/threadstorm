#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
./build.sh
exec java '-Dfile.encoding=UTF-8' -cp out Main "$@"
