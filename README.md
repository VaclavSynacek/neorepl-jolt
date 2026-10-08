# neorepl

A terminal REPL for Clojure (and friends) built on nREPL. It's meant as a
spiritual successor to [REPLy](https://github.com/trptcolin/reply), with a
proper line editor, the core of CIDER's tooling and a CLI that works just as
well for scripts and coding agents as it does for people.

It's early days: there's a REPL and a handful of command line tools, with the
first bits of CIDER's tooling. [PLAN.md](PLAN.md) has the details on where
this is going.

## Usage

There's no release yet. From a checkout,
[bbin](https://github.com/babashka/bbin) puts a `neorepl` command on your
path:

```shell
bbin install .
```

`neorepl` connects to the nREPL server whose `.nrepl-port` file is in the
current directory or one of its parents (`neorepl ports` lists the ones it
can see) and starts a REPL. Enter evaluates the input once its forms are
complete, Ctrl-C interrupts an evaluation and Ctrl-D exits. Code gets
highlighted, values get pretty printed and the history is kept per project.
TAB completes, the arglists of the function you're calling show up after
the cursor and C-x C-d shows the documentation of what's at the cursor.
Commands start with a comma, the CIDER way: `,doc`, `,source`, `,apropos`,
`,macroexpand`, `,pst` for the last error's stack trace, and `,help` for
the rest.
Without a terminal, say in a pipe or Emacs' `M-x shell`, it works like
`clojure.main`'s REPL instead.

`neorepl eval` evaluates code on the same server:

```shell
neorepl eval '(+ 1 2)'
neorepl eval --format json -f script.clj
echo '(range 3)' | neorepl eval -
```

Evaluations share a session, so `*ns*`, `*1` and `*e` carry over from one
call to the next. `--session NAME` keeps separate ones apart, `neorepl
sessions` lists them and `neorepl sessions close NAME` starts one over.
`neorepl eval --help` has the rest.

`neorepl load` loads a file the way `load-file` does, which is how you get
your changes into the running program after editing it:

```shell
neorepl load src/my/app.clj
```

`neorepl doc`, `neorepl source`, `neorepl apropos` and `neorepl pst` do what
their `clojure.repl` namesakes do, `neorepl macroexpand` expands macros and
`neorepl complete` lists completions, all using cider-nrepl when the server
has it:

```shell
neorepl doc map
neorepl source --ns my.app my.app/start
neorepl pst --format json
```

`neorepl check` finds unbalanced parens, brackets and braces, the most
common way for an edit (or a coding agent) to break a Clojure file. It
doesn't need a server:

```shell
$ neorepl check src/my/app.clj
src/my/app.clj:12:1: Unclosed (
```

## Development

neorepl runs from the same source on the JVM (Java 17+, Clojure 1.10+) and on
[babashka](https://babashka.org) (1.13.225+). The tests run on both:

```shell
bb test             # on babashka
bb test:jvm         # on the JVM, with the default Clojure version
bb test:jvm :1.10   # ... or with an older one (:1.10, :1.11)
```

## License

Copyright © 2026 Bozhidar Batsov and contributors.

Distributed under the Eclipse Public License 1.0, the same as Clojure.
