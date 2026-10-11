#!/usr/bin/env bash
# Requires an external JVM nREPL server. All spawned processes have timeouts.
set -Eeuo pipefail
cd "$(dirname "$0")/.."
port=${JVM_NREPL_PORT:-17989}
mkdir -p target
runtime=$(mktemp -d "$PWD/target/jolt-terminal-regression.XXXXXX")
session="neorepl-regression-$$"
trap 'tmux kill-session -t "$session" 2>/dev/null || true; rm -rf "$runtime"' EXIT
trap 'echo "Terminal regression failed at line $LINENO" >&2; tmux capture-pane -pt "$session" -S -100 >&2 || true' ERR
export XDG_CACHE_HOME="$runtime/cache" XDG_STATE_HOME="$runtime/state"
mkdir -p "$XDG_CACHE_HOME" "$XDG_STATE_HOME"
launch() {
  tmux new-session -d -s "$session" -x 100 -y 35 -c "$PWD" 'exec bash'
  tmux set-option -t "$session" remain-on-exit on
  tmux send-keys -t "$session" -l "$1"
  tmux send-keys -t "$session" Enter
}
wait_for() {
  local needle=$1 output
  for ((i=0; i<100; i++)); do
    output=$(tmux capture-pane -pt "$session")
    [[ $output == *"$needle"* ]] && return 0
    sleep .05
  done
  printf 'Missing %s\n%s\n' "$needle" "$output" >&2
  return 1
}
wait_line() {
  for ((i=0; i<100; i++)); do
    tmux capture-pane -pt "$session" | grep -Fqx "$1" && return 0
    sleep .05
  done
  echo "Missing complete line: $1" >&2
  return 1
}
type_code() { tmux send-keys -t "$session" -l "$1"; tmux send-keys -t "$session" Enter; }
quit() {
  type_code ',quit'
  for ((i=0; i<100; i++)); do
    [[ $(tmux display-message -pt "$session" '#{pane_dead}') == 1 ]] && break
    sleep .05
  done
  [[ $(tmux display-message -pt "$session" '#{pane_dead_status}') == 0 ]]
  tmux kill-session -t "$session"
}
printf -v command 'exec env XDG_CACHE_HOME=%q XDG_STATE_HOME=%q timeout 120s jolt -M:jolt --port %q' "$XDG_CACHE_HOME" "$XDG_STATE_HOME" "$port"
launch "$command"
wait_for 'user=>'
type_code '(do (print "PARTIAL") (flush) :value)'
wait_line ':value'
screen=$(tmux capture-pane -pt "$session")
grep -qx 'PARTIAL' <<< "$screen"
grep -qx ':value' <<< "$screen"
type_code '(do (future (Thread/sleep 600) (print "LATE-") (flush) (println "OUT")) nil)'
sleep .2
tmux send-keys -t "$session" -l '(+ 123'
wait_line 'LATE-OUT'
screen=$(tmux capture-pane -pt "$session")
grep -qx 'LATE-OUT' <<< "$screen"
grep -q '^user=> (+ 123' <<< "$screen"
[[ $screen != *'123LATE-'* ]]
tmux send-keys -t "$session" C-c
sleep .1
type_code '(+ 1'
tmux send-keys -t "$session" -l '2)'
tmux send-keys -t "$session" Up Home Right Right Right Delete
tmux send-keys -t "$session" -l '4'
tmux send-keys -t "$session" Enter
wait_line '6'
tmux send-keys -t "$session" -l '(identity "日本語😀")'
tmux send-keys -t "$session" End Left Left BSpace
tmux send-keys -t "$session" -l '漢'
tmux send-keys -t "$session" Enter
wait_line '"日本語漢"'
# Crossing the ASCII hint overlay's row boundary must use native redisplay.
wide=$(printf '%0130d' 0 | tr 0 X)
tmux send-keys -t "$session" -l "(count \"$wide\")"
wait_for 'user=> (count "'
tmux send-keys -t "$session" Enter
wait_line '130'
type_code '(do (print "Name: ") (flush) (read-line))'
wait_for 'Name: '
type_code 'bob'
wait_line '"bob"'
type_code '(do (println "BUSY") (flush) (Thread/sleep 60000))'
wait_line 'BUSY'
# Saturate the old 4096-byte queue: Ctrl-C must still reach the evaluation.
paste=$(printf '%05000d' 0)
tmux send-keys -t "$session" -l "$paste"
start=$SECONDS
tmux send-keys -t "$session" C-c
wait_for 'The evaluation was interrupted'
[[ $((SECONDS - start)) -lt 3 ]]
tmux send-keys -t "$session" C-c
sleep .1
quit
history=$(find "$XDG_STATE_HOME" -type f ! -name '*.lock' | head -1)
[[ -n $history ]]
timeout 15s jolt -e '(let [h (read-string (slurp (first *command-line-args*)))] (assert (some #{"(+ 4\n2)"} h)) (assert (not (some #{"bob"} h))))' -- "$history"
# A second process restores a complete multiline history entry, not fragments.
launch "$command"
wait_for 'user=>'
tmux send-keys -t "$session" Up
sleep .1
tmux send-keys -t "$session" C-c
sleep .1
quit
# Shutdown hooks must restore termios while an input callback is active.
printf -v command 'saved=$(stty -g); env XDG_CACHE_HOME=%q XDG_STATE_HOME=%q timeout 30s jolt -M:jolt --port %q; status=$?; actual=$(stty -g); stty "$saved"; [[ $saved == $actual ]] && echo RESTORED:$status; exit' "$XDG_CACHE_HOME" "$XDG_STATE_HOME" "$port"
launch "$command"
wait_for 'neorepl connected'
sleep .1
shell_pid=$(tmux display-message -pt "$session" '#{pane_pid}')
# The shell's only child is timeout, whose only child is the Jolt client.
timer_pid=$(pgrep -P "$shell_pid" | head -1)
client_pid=$(pgrep -P "$timer_pid" | head -1)
kill -TERM "$client_pid"
wait_for 'RESTORED:143'
tmux kill-session -t "$session"
# Initialization failure after acquiring the terminal and repeated close must
# unwind on the owner thread, restoring termios and allowing a second editor.
printf -v command 'saved=$(stty -g); timeout 20s jolt -A:jolt test/jolt-lifecycle-check.clj; status=$?; actual=$(stty -g); stty "$saved"; [[ $status == 0 && $saved == $actual ]] && echo UNWIND_OK; exit'
launch "$command"
wait_for 'EXPECTED_FAILURE'
wait_for 'LIFECYCLE_OK'
wait_for 'UNWIND_OK'
tmux kill-session -t "$session"
echo 'Terminal output, multiline editing/history, Unicode, stdin, paste, shutdown and rollback regressions passed.'
