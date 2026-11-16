#!/usr/bin/env bash
# Boots a real dedicated 26.3 server with all registry plugins + the selftest plugin,
# which exercises the Lua API in-game, logs PASS/FAIL and stops the server.
set -euo pipefail
cd "$(dirname "$0")/.."
rm -rf run/mcaddons run/world
mkdir -p run/mcaddons/plugins
[ -d registry/plugins ] || git clone -q --depth 1 https://github.com/RandomPixelStudios/McAddons-Plugins.git registry
cp -r registry/plugins/* test-plugins/* run/mcaddons/plugins/
echo "eula=true" > run/eula.txt
printf "online-mode=false\nlevel-type=minecraft\\:flat\n" > run/server.properties
timeout 600 ./gradlew :fabric-26.3:runServer | tee run/server-test.log
grep -q "SELFTEST RESULT: ALL PASSED" run/server-test.log
grep -q "Installed Welcome" run/server-test.log
echo "SMOKE TEST PASSED (API checks + GitHub install)"
