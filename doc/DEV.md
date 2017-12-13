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

## Linting

Run:

```bash
codeclimate analyze
```

From the repo root to lint.
