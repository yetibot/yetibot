# yetibot

It's a yeti. It's a bot. You can find it on campfire, awaiting your command.

<img src="https://www.decide.com/img/yeti.png" />

## Setup

Configure your system's environment variables and run it.

```
export CAMPFIRE_API_KEY=
export CAMPFIRE_ROOM=
export CAMPFIRE_ROOM_ID=
export CAMPFIRE_SUBDOMAIN=

export JENKINS_URI=
export JENKINS_USER=
export JENKINS_PASS=

export MEME_USER=
export MEME_PASS=
```


## Usage

All commands are prefixed by `yetibot` (or just `yeti` for short). yetibot ignores
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

## License

Copyright &copy; 2011 Trevor Hartman. Distributed under the [Eclipse Public License 1.0](http://opensource.org/licenses/eclipse-1.0.php), the same as Clojure.

