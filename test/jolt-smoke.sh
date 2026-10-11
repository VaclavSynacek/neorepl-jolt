#!/usr/bin/env bash
# Start `jolt nrepl-server 17888` in another terminal, then run this script.
# Override the port with JOLT_NREPL_PORT. No named-session persistence on Jolt.
set -euo pipefail
cd "$(dirname "$0")/.."
port=${JOLT_NREPL_PORT:-17888}
[[ $(jolt -M:jolt eval --port "$port" '(+ 1 2)') == 3 ]]
json=$(jolt -M:jolt eval --port "$port" --format json '(do (println "hello") {:answer 42})')
[[ $json == *'"status":"ok"'* && $json == *'"out":"hello\n"'* && $json == *'{:answer 42}'* ]]
jolt -M:jolt doc --port "$port" map | grep -q clojure.core/map
jolt -M:jolt check src/neorepl/main.clj jolt/neorepl/jolt.clj
set +e
jolt -M:jolt eval --port "$port" '(throw (ex-info "expected failure" {}))'
status=$?
set -e
[[ $status == 1 ]]
echo 'Jolt non-interactive smoke tests passed.'
