# Yetibot Getting Started

To get a Yetibot running you need two things:

1. Some config
1. A way to run it

## Config

A very minimal config would be:

```clojure
{:yetibot
  {:adapters {:freenode {:type "irc",
                        :username "my-yetibot",
                        :host "chat.freenode.net",
                        :port "7070",
                        :ssl "true"}}}}
```

This instructs Yetibot to join freenode with the username `my-yetibot` (change
it to whatever you like).

For full config see the
[CONFIGURATION](https://github.com/devth/yetibot.core/blob/master/doc/CONFIGURATION.md)
docs.

## Run it

There are a few ways to quickly run a Yetibot:

1. Docker - [read the Yetibot on Docker docs](doc/DOCKER.md)
1. Grab an archive of the source from the [Yetibot
   releases](https://github.com/devth/yetibot/releases), unzip, put the config
   in place and `lein run`
1. Clone the source of this repo, put the config in place and `lein run`

As an example, here's how you could get the latest code from `master`, extract,
put config in place, and run it (assumes you already have
[Leiningen](https://github.com/technomancy/leiningen) installed):

```bash
cd /tmp
curl https://codeload.github.com/devth/yetibot/tar.gz/master | tar xvz
cd yetibot-master
cat << EOF > config.edn
{:yetibot
 {:adapters
  {:freenode
   {:type "irc"
    :username "my-yetibot"
    :host "chat.freenode.net"
    :port "7070"
    :ssl "true"}}}}
EOF
YB_LOG_LEVEL=debug CONFIG_PATH=config.edn lein run
```

Once it starts up you'll see a log like:

```
17-05-28 23:27:56 deep.local INFO [yetibot.core.loader:41] - ☑ Loaded 84 namespaces matching [#"^yetibot\.(core\.)?commands.*" #"^.*plugins\.commands.*"]
```

At this point it should be connected to Freenode. Trying running a command
against it:

```bash
/msg my-yetibot !echo Hello, Yetibot!
```

And you should get a reply:

```bash
my-yetibot: Hello, Yetibot!
```

**NB**: Soon there will be a way to run a Yetibot with zero config in local REPL
mode :zap: #628 :zap:

## Questions

If these docs don't work for you please [open an
issue](https://github.com/devth/yetibot/issues/new)!

You can also try setting an env var `YB_LOG_LEVEL=debug` when running.
