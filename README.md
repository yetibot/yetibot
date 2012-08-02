# yetibot

It's a yeti. It's a bot. You can find it on campfire, awaiting your command.

<img src="https://www.decide.com/img/yeti.png" />

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

All commands are prefixed by `yetibot` (or just `yeti` for short or `!` for shortest). yetibot ignores
everything else.

### Jenkins

___status___

Returns status and date of the last build, whether it's currently building,
who it was started by, and the changeset.

```
yetibot jen status [jenkins job name]
```

___build___

```
yetibot jen build [job name]
```

___list___

Takes either a number or a word to match job names on. Number returns the first N
jobs and word returns all jobs matching the word.

```
yetibot jen list 2
yetibot jen list model
```

### Meme generator

___popular___

Shows the first result of popular instances from the API. In the future it'll probably grab a random result instead.

```
yetibot meme popular
```

___trending___

Shows the list of trending generators.

```
yetibot meme trending
```

___search___

Searches the generators by name.

```
yetibot meme search interesting
```

___generator___

Generates a meme and returns the image url to chat.

```
yetibot meme interesting I don't always code / but when I do, I use clojure
```

### Help

yetibot self-documents itself using the docstrings of its various commands. Ask it
for help.

```
!help
```

Its reply will be:

```
These are the topics I know about. Use help <topic> for more details.
help
clojure
scala
http
ssh
wolfram
meme
urban
jen
poke
image
```

## License

Copyright &copy; 2012 Trevor Hartman. Distributed under the [Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php), the same as Clojure.

