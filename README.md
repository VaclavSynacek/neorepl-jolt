# neorepl-jolt

This is a port of [neorepl](https://github.com/nrepl/neorepl) to [Jolt](https://github.com/jolt-lang/jolt) that aims to maintain the original functionality, uses GNU Readline instead of JLine, and was created with heavy use of AI automation.

## Setup

Requires Jolt, GNU Readline 8, and a C compiler for the one-time interactive bridge build. No JVM or babashka needed.

```sh
git clone https://github.com/VaclavSynacek/neorepl-jolt.git
cd neorepl-jolt
bash build/jolt-native.sh
```

On Termux, build with `CC=clang bash build/jolt-native.sh`.

## Start a server

```sh
jolt nrepl-server 7888
```

## Connect

In another terminal, from the checkout:

```sh
jolt -M:jolt --port 7888
```

Enter evaluates complete forms; Ctrl-J inserts a newline, Tab completes, and `,quit` or Ctrl-D exits.

## CLI

```sh
jolt -M:jolt eval --port 7888 '(+ 1 2)'
jolt -M:jolt doc --port 7888 map
```

CLI and piped input do not need Readline or the bridge. TCP only; available features depend on the server.

## License

Copyright © 2026 Bozhidar Batsov and contributors. [Eclipse Public License 1.0](LICENSE).
GNU Readline is GPL-licensed; review licensing before redistribution.
