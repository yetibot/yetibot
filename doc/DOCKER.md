# Docker

The official Yetibot image is
[devth/yetibot](https://hub.docker.com/r/devth/yetibot/). It's built on the
official [Clojure image](https://hub.docker.com/_/clojure/).

## Configuration

Configuration can be specified as env vars or passed in via a mounted volume.
See
[CONFIGURATION](https://github.com/devth/yetibot.core/blob/flat-config/doc/CONFIGURATION.md)
docs for more info.

## Ports

Yetibot runs a webapp on port `3000`. You may optionally expose it via `-P` or
`-p` to choose your own host port mapping.

## Running

Start up Yetibot in detached mode with port 3000 mapped and IRC configured via
env:

```
docker run --name yetibot \
  -d -p 3000:3000 \
  -e YETIBOT_LOG_LEVEL="trace" \
  -e YETIBOT_ADAPTERS_IRC_TYPE="irc" \
  -e YETIBOT_ADAPTERS_IRC_HOST="chat.freenode.net" \
  -e YETIBOT_ADAPTERS_IRC_PORT="7070" \
  -e YETIBOT_ADAPTERS_IRC_SSL="true" \
  -e YETIBOT_ADAPTERS_IRC_USERNAME="yetibot_`whoami`" \
  devth/yetibot:snapshot
```

<em>Note, if you're using Docker Machine, you can view the webapp at its IP
rather than localhost.</em>

Tail its logs:

```
docker logs -f yetibot
```

## Troubleshooting

To run an ephemeral interactive shell and poke around instead of running Yetibot:

```
docker run --rm -it --name yetibot \
  -e YETIBOT_LOG_LEVEL="trace" \
  -e YETIBOT_ADAPTERS_IRC_TYPE="irc" \
  -e YETIBOT_ADAPTERS_IRC_HOST="chat.freenode.net" \
  -e YETIBOT_ADAPTERS_IRC_PORT="7070" \
  -e YETIBOT_ADAPTERS_IRC_SSL="true" \
  -e YETIBOT_ADAPTERS_IRC_USERNAME="yetibot_`whoami`" \
  devth/yetibot \
  /bin/bash
```

