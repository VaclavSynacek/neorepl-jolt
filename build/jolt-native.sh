#!/usr/bin/env bash
# Build once during development/packaging, never during REPL startup.
set -euo pipefail
cd "$(dirname "$0")/.."
case "$(uname -s)" in
  Linux) suffix=so; link=(-shared) ;;
  Darwin) suffix=dylib; link=(-dynamiclib) ;;
  *) echo 'The Jolt Readline backend currently supports Linux and macOS only.' >&2; exit 1 ;;
esac
output="target/jolt-native/libneorepl-readline.$suffix"
mkdir -p "$(dirname "$output")"
# Build to a unique staging path and publish atomically for concurrent builds.
tmp=$(mktemp "$output.XXXXXX")
trap 'rm -f "$tmp"' EXIT
"${CC:-cc}" "${link[@]}" -fPIC -Wall -Wextra -Werror -O2 \
  jolt/neorepl/readline_bridge.c -o "$tmp"
chmod 755 "$tmp"
mv "$tmp" "$output"
printf '%s\n' "$output"
