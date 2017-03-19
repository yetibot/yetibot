# Yetibot Getting Started

To get a Yetibot running you need two things:

1. Some config
2. A way to run it

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
2. Grab an archive of the source from the [Yetibot
   releases](https://github.com/devth/yetibot/releases), unzip, put the config
   in place and `lein run`
3. Clone the source of this repo, put the config in place and `lein run`

## Questions

If these docs don't work for you please [open an
issue](https://github.com/devth/yetibot/issues/new)!
