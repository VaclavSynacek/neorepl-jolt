# neorepl

A terminal REPL for Clojure (and friends) built on nREPL. It's meant as a
spiritual successor to [REPLy](https://github.com/trptcolin/reply), with a
proper line editor, the core of CIDER's tooling and a CLI that works just as
well for scripts and coding agents as it does for people.

It's early days: so far there's `neorepl eval` and not much else.
[PLAN.md](PLAN.md) has the details on where this is going.

## Usage

There's no release yet. From a checkout,
[bbin](https://github.com/babashka/bbin) puts a `neorepl` command on your
path:

```shell
bbin install .
```

`neorepl eval` evaluates code on the nREPL server whose `.nrepl-port` file is
in the current directory or one of its parents:

```shell
neorepl eval '(+ 1 2)'
neorepl eval --format json -f script.clj
echo '(range 3)' | neorepl eval -
```

Evaluations share a session, so `*ns*`, `*1` and `*e` carry over from one
call to the next. `neorepl eval --help` has the rest.

## Development

neorepl runs from the same source on the JVM (Java 17+, Clojure 1.10+) and on
[babashka](https://babashka.org) (1.13.221+). The tests run on both:

```shell
bb test             # on babashka
bb test:jvm         # on the JVM, with the default Clojure version
bb test:jvm :1.10   # ... or with an older one (:1.10, :1.11)
```

## License

Copyright © 2026 Bozhidar Batsov and contributors.

Distributed under the Eclipse Public License 1.0, the same as Clojure.
