#!/usr/bin/env bash
# Standalone, bounded Jolt test matrix. Needs existing jolt/java/clojure/tmux/cc.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
mkdir -p target
work=$(mktemp -d "$PWD/target/jolt-ci.XXXXXX")
export NEOREPL_TEST_WORK="$work" XDG_CACHE_HOME="$work/cache" XDG_STATE_HOME="$work/state" TMPDIR="$work/tmp"
mkdir -p "$XDG_CACHE_HOME" "$XDG_STATE_HOME" "$TMPDIR"
native_pid= jvm_pid=
cleanup() {
  for pid in "$native_pid" "$jvm_pid"; do
    [[ -n $pid ]] && kill "$pid" 2>/dev/null || true
  done
  for pid in "$native_pid" "$jvm_pid"; do
    [[ -n $pid ]] && wait "$pid" 2>/dev/null || true
  done
  rm -rf "$work"
}
trap cleanup EXIT
trap 'echo "Jolt matrix failed at line $LINENO" >&2; tail -50 "$work/native.log" "$work/jvm.log" 2>/dev/null || true' ERR
bash build/jolt-native.sh
# Use published dependencies already declared by the project, not host installs.
classpath=$(clojure -Spath)
timeout 180s jolt -A:jolt -e '(require (quote [jolt.nrepl :as s])) (def stop (s/start 0 nil {:port-file nil})) (spit (str (System/getenv "NEOREPL_TEST_WORK") "/native.port") (str (:port (meta stop)))) @(promise)' > "$work/native.log" 2>&1 &
native_pid=$!
timeout 180s java -cp "$classpath" clojure.main -e '(require (quote [nrepl.server :as s])) (def server (s/start-server :bind "127.0.0.1" :port 0)) (spit (str (System/getenv "NEOREPL_TEST_WORK") "/jvm.port") (str (:port server))) @(promise)' > "$work/jvm.log" 2>&1 &
jvm_pid=$!
for ((i=0; i<200; i++)); do
  [[ -s $work/native.port && -s $work/jvm.port ]] && break
  kill -0 "$native_pid" "$jvm_pid"
  sleep .05
done
[[ -s $work/native.port && -s $work/jvm.port ]]
JOLT_NREPL_PORT=$(< "$work/native.port")
JVM_NREPL_PORT=$(< "$work/jvm.port")
export JOLT_NREPL_PORT JVM_NREPL_PORT
# Pipes remain usable when the native artifact is deliberately unavailable.
piped=$(printf '(+ 1 2)\n' | NEOREPL_READLINE_BRIDGE=/nonexistent timeout 15s jolt -M:jolt --port "$JVM_NREPL_PORT")
[[ $piped == *'user=> 3'* ]]
timeout 45s bash test/jolt-smoke.sh
JOLT_NREPL_PORT="$JVM_NREPL_PORT" timeout 45s bash test/jolt-smoke.sh
timeout 20s jolt -A:jolt test/jolt-editor-check.clj
timeout 40s jolt -A:jolt test/jolt-client-check.clj "$JVM_NREPL_PORT" < /dev/null
timeout 30s jolt -A:jolt:test -e '(require (quote [clojure.test :as t]) (quote neorepl.cli-test) (quote neorepl.lexer-test) (quote neorepl.json-test) (quote neorepl.render-test)) (let [r (t/run-tests (quote neorepl.cli-test) (quote neorepl.lexer-test) (quote neorepl.json-test) (quote neorepl.render-test))] (System/exit (if (zero? (+ (:fail r) (:error r))) 0 1)))'
session_count() {
  timeout 15s jolt -A:jolt -e '(require (quote [neorepl.jolt :as j]) (quote [neorepl.nrepl :as c])) (let [x (c/connect (assoc (j/context) :port (Long/parseLong (first *command-line-args*))))] (try (println (count (some :sessions (c/request x {:op "ls-sessions"})))) (finally (c/close x))))' -- "$JVM_NREPL_PORT"
}
before=$(session_count)
workers=()
for i in 1 2 3 4; do
  timeout 20s jolt -M:jolt eval --port "$JVM_NREPL_PORT" --session concurrent "$i" > "$work/worker-$i.out" &
  workers+=("$!")
done
for pid in "${workers[@]}"; do wait "$pid"; done
[[ $(session_count) -eq $((before + 1)) ]]
timeout 15s jolt -M:jolt sessions --port "$JVM_NREPL_PORT" close concurrent
[[ $(session_count) -eq $before ]]
timeout 45s bash test/jolt-terminal-smoke.sh
timeout 90s bash test/jolt-terminal-regressions.sh
echo 'Jolt matrix passed against built-in Jolt and JVM nREPL servers.'
