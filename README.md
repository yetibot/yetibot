# yetibot

Find me on Campfire, awaiting your command.

<img src="https://www.decide.com/img/yeti.png" />

[![Build Status](https://travis-ci.org/devth/yetibot.png?branch=master)](https://travis-ci.org/devth/yetibot)

## Setup

Configure your `ENV` and `lein run` it.

```
export CAMPFIRE_API_KEY=
export CAMPFIRE_ROOM=
export CAMPFIRE_ROOM_ID=
export CAMPFIRE_SUBDOMAIN=

export JENKINS_URI=
export JENKINS_USER=
export JENKINS_API_KEY=
# Default job to build when running !jenkins build with no args
export JENKINS_DEFAULT_JOB=

export MEME_USER=
export MEME_PASS=

# A list of host aliases
export SSH_SERVERS=dev,test
export SSH_dev=<dev.example.com>
export SSH_test=<test.example.com>
# The private key to connect to all hosts. This may be configurable per-host in the future.
export SSH_PRIVATE_KEY_PATH=
export SSH_USERNAME=

export WOLFRAM_APP_ID=

# Sounds to play when specific users enter the room
export WELCOME_IDS=<userid1>,<userid2>
# User 1
export WELCOME_<userid1>=yeah
# User 2
export WELCOME_<userid2>=secret
```


## Usage

All commands are prefixed by `!`. yetibot ignores everything else.

TODO: more examples

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

```
!meme grumpy cat: `catfact` / False
```

<img src="http://cdn.memegenerator.net/instances/500x/33734863.jpg" />

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
!help all
```

```
head <list> # returns the first item from the <list>
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
clj <expression> # evaluate a clojure expression
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
help all # get help for all topics
help <topic> # get help for <topic>
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
catfact # fetch a random cat fact
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
random <list> # returns a random item where <list> is a comma-separated list of items.
  Can also be used to extract a random item when a collection is piped to random.
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
scala <expression> # evaluate a scala expression
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
wordnik define <word> # look up the definition for <word> on Wordnik
wordnik random # look up a random word on Wordnik
wordnik wotd # look up the Word of the Day on Wordnik
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
http <code>                 # look up http status code
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
users random # get a random user
users # list all users presently in the room
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
ssh <server> <command> # run a command on <server>
ssh servers # list servers configured for ssh access
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
attack <name> # attacks a person in the room
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
uptime # list uptime in milliseconds
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
js <expression> # evaluate a javascript expression
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
cls # clear screen after your co-worker posts something inappropriate
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
wolfram <query> # search for <query> on Wolfram Alpha
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
curl <options> <url> # execute standard curl tool
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
info <topic> # retrieve info about <topic> from DuckDuckGo
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
order reset # reset the orders list
order show # show the current order
order for <person>: <food> # order <food> for someone other than yourself
order <food> # add (or replace) your food for the current order
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
classnamer # retrieves a legit OO class name
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
tail <list> # returns the last item from the <list>
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
meme popular                # list random popular meme instances from the top 20 in the last day
meme popular <generator>    # list random popular meme instances for <generator> from the top 20 in the last day
meme trending               # list trending generators
meme <generator>: <line1> / <line2> # generate an instance
meme search <term>          # query available meme generators
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
ascii <text> # generates ascii art representation of <text>
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
urban random # fetch a random definition from Urban Dictionary
urban <query> # search for <query> on Urban Dictionary
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
jen build # build default job if configured
jen build <job-name>
jen status <job-name>
jen list                    # lists first 20 jenkins jobs
jen list <n>                # lists first <n> jenkins jobs
jen list <string>           # lists jenkins jobs containing <string>
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
xargs <cmd> <list> # run <cmd> for every item in <list>; behavior is similar to xargs(1)'s xargs -n1
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
complete <phrase> # complete phrase from Google Complete
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
poke                        # NEVER do this
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
image top <query> # fetch the first image from google images
image <query> # fetch a random result from google images
▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬
notit reset # resets the current not-it list
notit show # show the current list of users registered as not-it
notit # register a user as not-it
```

## Plugins

YetiBot [looks in namespaces](https://github.com/devth/yetibot/blob/master/src/yetibot/core.clj#L100-104)
starting with "plugins" when loading commands and observers. It also [ignores](https://github.com/devth/yetibot/blob/master/.gitignore#L10)
`src/plugins` so that you can symlink it to a directory outside of YetiBot, which
might be stored in some other repository.

## License

Copyright &copy; 2012-2013 Trevor Hartman. Distributed under the [Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php), the same as Clojure.
