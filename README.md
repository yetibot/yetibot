# yetibot

You can treat yetibot as a communal command line. It works well for:

 - **teaching**: how to run internal automation, language evaluation for JS,
   Scala, Clojure
 - **productivity**: automating things around Jenkins, JIRA, running SSH
   commands on various servers, and interacting with internal APIs via private
   yetibot plugins
 - **fun**: google image search, gif lookups, meme generation

In addition to a wealth of commands (see `!help all` to view them), it supports
unix-style piping and arbitrarily-nested sub expressions.

![yeti](yeti.png)

[![Build Status](https://travis-ci.org/devth/yetibot.png?branch=master)](https://travis-ci.org/devth/yetibot)

## Configuration

Configuration lives at `config/config.edn`, which is `.gitignore`d. See
[config/config-sample.edn](config/config-sample.edn) for a sample config.
`mv` this to `config/config.edn` and fill in the blanks to get started.

## Running

Once configuration is in place, simply `lein run`.

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

Backticks provide a lighweight syntax for sub-expressions, but they can't be
nested.

```
!meme grumpy cat: `catfact` / False
```

<img src="http://cdn.memegenerator.net/instances/500x/33734863.jpg" />


### Nested sub-expressions

For arbitrarily-nested sub-expressions, use `$(expr)` syntax, which
disambiguates the open and closing of an expressions.

```
!meme chemistry: $(number $(js parseInt('$(weather 98105 | head 2 | tail)')))
```

<img src="http://i.imgflip.com/4xby8.jpg" />


### Combo

```
!echo `repeat 10 echo i don't always repeat myself but | join`…StackOverflowError | meme interesting:
```

<img src="http://cdn.memegenerator.net/instances/500x/34461434.jpg" />

### Help

yetibot self-documents itself using the docstrings of its various commands. Ask it
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

YetiBot [looks in namespaces](https://github.com/devth/yetibot/blob/master/src/yetibot/core.clj#L100-104)
starting with "plugins" when loading commands and observers. It also
[ignores](https://github.com/devth/yetibot/blob/master/.gitignore#L10)
`src/plugins` so that you can symlink it to a directory outside of YetiBot,
which might be stored in some other repository.


## How it works

Curious how the internals of YetiBot works? At a high level:

- commands are run through a parser built on
  [InstaParse](https://github.com/Engelberg/instaparse):
  https://github.com/devth/yetibot/blob/master/src/yetibot/parser.clj
- an InstaParse transformer is configured to evaluate expressions through the
  interpreter, which handles things like nested sub-expressions and piped
  commands:
  https://github.com/devth/yetibot/blob/master/src/yetibot/interpreter.clj
- [command namespaces](https://github.com/devth/yetibot/tree/master/src/yetibot/commands)
  are `hook`ed into the interpreter's `handle-cmd` function using a `cmd-hook`
  macro and triggered via regex prefix matching:
  https://github.com/devth/yetibot/blob/master/src/yetibot/hooks.clj

## Getting help

If the doc or implementation code don't serve you well, I'm always interested in
learning why and improving things. Open an issue or submit a pull request to get
things moving!

## License

Copyright &copy; 2012-2013 Trevor Hartman. Distributed under the [Eclipse Public
License 1.0](http://opensource.org/licenses/eclipse-1.0.php), the same as
Clojure.
