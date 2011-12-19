# yetibot

It's a yeti. It's a bot. You can find it on campfire, awaiting your command.

<img src="http://www.decide.com/img/yeti.png" />

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

### Jenkins

___status___

```
yetibot jen status [jenkins job name]

SUCCESS at Wed Nov 16. Started by trevor.
Currently building? false
[Changeset]
```

___build___

```
yetibot jen build [job name]

Building [job name]
```

___list___

```
yetibot jen list 2

job-1
job-2
```

yetibot jen list model

[lists all jobs containing "model"]
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

