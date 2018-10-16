# Yetibot development

## Setup

### Postgres

Yetibot needs a postgres database. It defaults to `yetibot` but this is
configurable. Ensure you have Postgres installed, then create the database:

```bash
createdb yetibot
```

## Build a Docker image

```bash
lein do clean, uberjar
```

## REPL

Start up a development REPL with:

```
lein repl
```

Run:

```clojure
(start)
```

To load a core set of commands and connect to any configured adapters.

At this point a typical dev workflow would be to iteratively write and reload
code from your editor as is common in the Clojure community. See
[Essentials](http://clojure-doc.org/articles/content.html#essentials) for docs
on setting up various editors for Clojure development.

You can also optionally load all commands from the REPL using:

```clojure
(load-all)
```

To fully reload and restart the adapters and database connections, use:

```clojure
(reset)
```

And to stop, simply use:

```clojure
(stop)
```

See source for
[`yetibot.core.repl`](https://github.com/yetibot/yetibot.core/blob/master/src/yetibot/core/repl.clj)
for more info.

## Linting

Run:

```bash
codeclimate analyze
```

From the repo root to lint.
