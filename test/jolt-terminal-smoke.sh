#!/usr/bin/env bash
# Requires a running Jolt nREPL server and existing tmux/cc/Readline.
set -euo pipefail
cd "$(dirname "$0")/.."
port=${JOLT_NREPL_PORT:-17888}
session="neorepl-test-$$"
trap 'tmux kill-session -t "$session" 2>/dev/null || true' EXIT
tmux new-session -d -s "$session" -x 100 -y 30 -c "$PWD" 'exec bash'
tmux set-option -t "$session" remain-on-exit on
tmux send-keys -t "$session" "exec jolt -M:jolt --port $port" Enter
wait_for() {
  local needle=$1 output
  for ((i=0; i<100; i++)); do
    output=$(tmux capture-pane -pt "$session")
    if [[ $output == *"$needle"* ]]; then return; fi
    sleep 0.1
  done
  printf 'Missing terminal output: %s\n%s\n' "$needle" "$output" >&2
  return 1
}
wait_for 'user=>'
tmux send-keys -t "$session" '(map '
wait_for '[f coll]'
tmux send-keys -t "$session" C-c
wait_for '^C'
[[ $(tmux display-message -pt "$session" '#{pane_current_command}') == jolt ]]
tmux send-keys -t "$session" '{:smoke 42}'
wait_for '{:smoke 42}'
colors=$(tmux capture-pane -ept "$session")
[[ $colors == *$'\e[35m42'* ]]
tmux send-keys -t "$session" C-c
tmux send-keys -t "$session" '(reduce-k' Tab
wait_for '(reduce-kv'
tmux send-keys -t "$session" C-x C-d
wait_for 'clojure.core/reduce-kv'
tmux send-keys -t "$session" C-c
tmux send-keys -t "$session" '(Thread/sleep 10000)' Enter
sleep 0.3
tmux send-keys -t "$session" C-c
wait_for "The server can't interrupt"
tmux send-keys -t "$session" C-c
wait_for 'Stopped waiting for the evaluation'
[[ $(tmux display-message -pt "$session" '#{pane_current_command}') == jolt ]]
tmux send-keys -t "$session" ',quit' Enter
for ((i=0; i<100; i++)); do
  [[ $(tmux display-message -pt "$session" '#{pane_dead}') == 1 ]] && break
  sleep 0.1
done
[[ $(tmux display-message -pt "$session" '#{pane_dead}') == 1 ]]
[[ $(tmux display-message -pt "$session" '#{pane_dead_status}') == 0 ]]
echo 'Readline highlighting, arglists, completion, docs and Ctrl-C tests passed.'
