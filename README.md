<p align="center">
  <img width="560"
    src="https://github.com/devth/yetibot/raw/master/img/yetibot_final.png?raw=true" />
</p>
<p align="center">
  <i>A chat bot written in Clojure, at your service.</i>
</p>

# Yetibot

[![Build Status](https://travis-ci.org/devth/yetibot.svg?branch=master)](https://travis-ci.org/devth/yetibot)
[![Yetibot](https://img.shields.io/clojars/v/yetibot.svg)](https://clojars.org/yetibot)
[![Yetibot on Docker Hub](https://img.shields.io/badge/docker-%E2%86%92-blue.svg)](https://hub.docker.com/r/devth/yetibot/)
[![CrossClj](https://img.shields.io/badge/API%20docs-yetibot-blue.svg)](https://crossclj.info/doc/yetibot/latest/index.html)
[![Ready for work](https://img.shields.io/waffle/label/devth/yetibot/ready.svg?label=ready%20for%20work)](https://waffle.io/devth/yetibot)
[![In Progress](https://img.shields.io/waffle/label/devth/yetibot/in%20progress.svg)](https://waffle.io/devth/yetibot)
[![Issue Count](https://img.shields.io/codeclimate/issues/github/devth/yetibot.svg?label=code%20climate)](https://codeclimate.com/github/devth/yetibot)
[![Codecov](https://img.shields.io/codecov/c/github/devth/yetibot.svg)](https://codecov.io/gh/devth/yetibot)

You can think of Yetibot as a **communal command line**. It excels at:

- **teaching**: how to run internal automation, language evaluation for JS,
  Scala, Clojure, and Haskell
- **productivity**: automating things around Jenkins, JIRA, running SSH
  commands on various servers, and interacting with internal APIs via private
  Yetibot plugins
- **fun**: Google image search, gif lookups, meme generation

Features that make Yetibot powerful and great, which is to say *fun*:

- **Unix-style pipes** allow tremendous expressiveness in chaining
  together complex and flexible commands.
- **Sub-expressions** let you embed the output of one command into an outer
  command. They can be nested as many levels deep as you can imagine (open a PR
  to add to [EXAMPLES](doc/EXAMPLES.md) if you come up with something crazy!).
- **Aliases** let you parameterize complex expressions and give them a name
  allowing your team to quickly build up idiomatic team-specific Yetibot usages
  (not just memes!).
- **Per-channel settings** let you store arbitrary config at the channel level,
  which can be used by commands or aliases to change the behavior of commands
  depending on which channel you're in (e.g. the default JIRA project for a
  channel).
- **Feature category toggle** lets you disable or enable entire
  [categories](https://github.com/devth/yetibot.core/blob/master/doc/CATEGORIES.md)
  of commands per-channel; useful for disabling gifs in the work-only channel 😁.

Take a look at the [usage examples](doc/EXAMPLES.md) to see some ~~fun~~ useful
ways it can be used.

To quickly try out Yetibot with minimal config,
read the blog post [Yetibot on Docker in 𝓧 minutes or less](http://devth.com/2016/yetibot-on-docker/).

## Changes in 0.4.0

0.4.0 decomplects mutable and immutable configuration in a
non-backward-compatible way. Please see
[CONFIGURATION](https://github.com/devth/yetibot.core/blob/master/doc/CONFIGURATION.md)
docs and port your existing config to the new structure.

## Yetibot users

Already using Yetibot? Please add yourself to the [list of Yetibot
users](https://github.com/devth/yetibot/wiki/Yetibot-users)!

## Contributing

See [CONTRIBUTING](CONTRIBUTING.md).

## Roadmap

Yetibot has been undergoing continuous improvement since its inception. These
are the immediate priorities, in addition to any bugfixes. Feedback and
contributions are very welcome!

- [ ] Write docs on how to develop Yetibot
- [x] Dockerize Yetibot. Done! Check out [the instructions](doc/DOCKER.md).
- [ ] Dockerize Datomic Pro Starter edition.
- [x] Integrate with [StackStorm](https://stackstorm.com/) for automation on
      steriods. Done! Check out
      [yetibot-stackstorm](https://github.com/devth/yetibot-stackstorm).
- [ ] Create a Heroku deploy button to make it easy to get started
- [ ] Make adapters plugable
- [ ] Create more examples of plugins (e.g. Travis)
- [x] Run a demo Yetibot instance — you can now talk to a Yetibot on Freenode in
  the `#yetibot` channel!
- [ ] Design & build yetibot.com — <em>In progress!</em>
- [x] Flatten the config and obtain it via
  [environ](https://github.com/weavejester/environ) to follow [12 Factor
  App](http://12factor.net/config) practices
  [#570](https://github.com/devth/yetibot/issues/570)

## Installation

There are three primary ways of installing Yetibot:

1. **Follow the [Docker instructions](doc/DOCKER.md)**: the fastest way if you're
   already using Docker.
1. **Clone this repo**: this gives you a standard Yetibot installation and
   provides a git-ignored place to store configuration. Run from the root dir
   with `lein run`.
1. **Make your own repo and depend on Yetibot**: this gives you ultimate
   customizability, allowing you to depend on custom Yetibot plugins or define
   your own commands in-project, and gives you control over where you store
   your config (manual management, commit to private git repo, etc...)

   [![Yetibot](https://img.shields.io/clojars/v/yetibot.svg)](https://clojars.org/yetibot)

## Configuration

See the
[CONFIGURATION](https://github.com/devth/yetibot.core/blob/master/doc/CONFIGURATION.md)
docs.

## Usage

All commands are prefixed by `!`.

### Pipes

Output from one command can be piped to another, like Unix pipes.

```
!complete does IE support | xargs echo %s? No, it is sucky.

does ie support html5? No, it is sucky.
does ie support css3? No, it is sucky.
does ie support svg? No, it is sucky.
does ie support media queries? No, it is sucky.
does ie support ftps? No, it is sucky.
does ie support png? No, it is sucky.
does ie support canvas? No, it is sucky.
does ie support @font-face? No, it is sucky.
does ie support webgl? No, it is sucky.
does ie support ttf? No, it is sucky.
```

### Backticks

Backticks provide a lightweight syntax for sub-expressions, but they can't be
nested.

```
!meme grumpy cat: `catfact` / False
```

<img src="http://cdn.memegenerator.net/instances/500x/33734863.jpg" />

### Nested sub-expressions

For arbitrarily-nested sub-expressions, use `$(expr)` syntax, which
disambiguates the open and closing of an expressions.

```
!meme philos: $(complete how does one $(users | random | letters | random) | random)
```

<img src="http://i.imgflip.com/z4d45.jpg" />

### Combo

```
!echo `repeat 4 echo i don't always repeat myself but | unwords`…StackOverflowError | meme interesting:
```

<img src="http://i.imgflip.com/z4d6f.jpg" />

### Aliases

You can build your own aliases at runtime. These are stored in the configured
database, so upon restart they are restored.

```
!alias nogrid = repeat 3 echo `repeat 3 meme grumpy: no | join`
```

Pipes can be used, but the right-hand side must be quoted in order to treat it
as a literal instead of being evaluated according to normal pipe behavior.

```
!alias i5 = "random | echo http://icons.wunderground.com/webcamramdisk/w/a/wadot/324/current.jpg?t=%s&.jpg"
```

You can specify placeholder arguments on the right-hand side using `$s` to
indicate all arguments, or `$n` (where n is a 1-based index of which arg).

```
!alias temp = "weather $s | head 2 | tail"
!temp 98104
=> 33.6 F (0.9 C), Overcast
```

### Adapter config

**IRC**: Yetibot can listen on any number of channels. You configure
channels in
[config.edn](https://github.com/devth/yetibot/blob/53cb4f01f6b6ad0be3f8061d9297a036453f3b9c/config/config-sample.edn#L33-L34).
You can also invite Yetibot to a channel at runtime using the IRC `/invite`
command:

```
/invite yetibot #whoa
```

When you invite Yetibot to a new channel, `config.edn` is overwritten, so next
time you restart Yetibot, it will re-join the same channels.

You can also use the `!room` command to tell yetibot to join or leave a channel.

```
!help room
room join <room> # join <room>
room leave <room> # leave <room>
room list # list rooms that yetibot is in
room set <key> <value> # configure a setting for the current room
room settings # show all chat settings for this room
room settings <key> # show the value for a single setting
```

**Slack**: bots can't join a channel on their own, they must be invited, so
room configuration doesn't apply. Instead, `/invite @yetibot` to any channel
that you're in, and `/kick @yetibot` if you want it to leave. NB: you might need
special privileges in order to kick.

**Campfire is no longer supported.** If you use Campfire, open an
issue and we can add it back in!

**Other chat platforms**: If your chat platform of choice is not supported, open
an issue. Adding adapters is quite easy.

#### Broadcast

If a room has `broadcast` set to `true`, Tweets will be posted to that room.
By default all rooms have it set to false. To enable:

```
!room set broadcast true
```

### Help

Yetibot self-documents itself using the docstrings of its various commands. Ask it
for `!help` to get a list of help topics. `!help all` shows fully expanded command
list for each topic.

```
!help | join ,
```

```
Use help <topic> for more details, !, <gen>that, alias, ascii, asciichart,
attack, buffer, catfact, chat, chuck, classnamer, clj, cls, complete, config,
count, curl, ebay, echo, eval, features, gh, giftv, grep, haiku, head, help,
history, horse, hs, http, image, info, jargon, jen, join, js, keys, list, log,
mail, meme, memethat, mustachefact, number, order, poke, poms, random, raw,
react, reload, repeat, rest, reverse, rhyme, scala, scalex, sed, set, sort, source,
split, ssh, status, tail, take, tee, twitter, update, uptime, urban, users,
vals, weather, wiki, wolfram, wordnik, words, xargs, xkcd, zen
```

## Plugins

Yetibot has a plugin-based architecture. Its core which all plugins depend on
is [yetibot.core](https://github.com/devth/yetibot.core).

[![yetibot.core](https://img.shields.io/clojars/v/yetibot.core.svg)](https://clojars.org/yetibot.core)

Yetibot will load all commands and observers with namespaces on the classpath
matching [these
regexes](https://github.com/devth/yetibot.core/blob/master/src/yetibot/core/loader.clj#L12-16).

This lets you build any number of independent plugin projects and combine them
via standard Leiningen dependencies.

## How it works

Curious how the internals of Yetibot works? At a high level:

1. commands are run through a
   [parser](https://github.com/devth/yetibot.core/blob/master/src/yetibot/core/parser.clj)
   built on [InstaParse](https://github.com/Engelberg/instaparse):
1. an [InstaParse
   transformer](https://github.com/devth/yetibot.core/blob/master/src/yetibot/core/interpreter.clj)
   is configured to evaluate expressions through the interpreter, which handles
   things like nested sub-expressions and piped commands
1. [command
   namespaces](https://github.com/devth/yetibot/tree/master/src/yetibot/commands)
   are
   [`hook`ed](https://github.com/devth/yetibot.core/blob/master/src/yetibot/core/hooks.clj)
   into the interpreter's `handle-cmd` function using a `cmd-hook` macro and
   triggered via regex prefix matching

## Getting help

If the docs or implementation code don't serve you well, please open a pull
request and explain why so we can improve the docs. Also feel free to open an
issue for feature requests!

## License

Copyright &copy; 2012-2017 Trevor Hartman. Distributed under the [Eclipse Public
License 1.0](http://opensource.org/licenses/eclipse-1.0.php), the same as
Clojure.

Logo designed by [Freeform Design Co](http://freeformdesign.co/).
