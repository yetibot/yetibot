# Yetibot on Docker

The official Yetibot image is
[devth/yetibot](https://hub.docker.com/r/devth/yetibot/). It's built on the
official [Clojure image](https://hub.docker.com/_/clojure/).

## Configuration

Configuration can be specified as env vars or passed in via a mounted volume.
See
[CONFIGURATION](https://github.com/devth/yetibot.core/blob/master/doc/CONFIGURATION.md)
docs for more info.

## Ports

Yetibot runs a webapp on port `3000`. You may optionally expose it via `-P` or
`-p` to choose your own host port mapping.

## Running

The most minimal incantation to run a Yetibot is:

```
docker run -e YB_ADAPTERS_IRC_TYPE="irc" devth/yetibot
```

This will join Freenode with a username like `yetibot_$rand` where `$rand` is a
random number between 0 and 1000. Watch the logs to see which was assigned. Once
it's connected, join freenode and:

```
/msg yetibot_$rand !echo i'm alive!
```

To start up Yetibot in detached mode with port 3000 mapped and IRC with SSL
fully configured via env:

```
docker run --name yetibot \
  -d -p 3000:3000 \
  -e YB_ADAPTERS_IRC_TYPE="irc" \
  -e YB_ADAPTERS_IRC_HOST="chat.freenode.net" \
  -e YB_ADAPTERS_IRC_PORT="7070" \
  -e YB_ADAPTERS_IRC_SSL="true" \
  -e YB_ADAPTERS_IRC_USERNAME="yetibot_`whoami`" \
  devth/yetibot
```

<em>Note, if you're using Docker Machine, you can view the webapp at its IP
rather than localhost.</em>

Tail its logs:

```
docker logs -f yetibot
```

### Mostly configured "oneliner"

A more complete example (though some config is still omitted):

```
docker run -d -p 80:3000 \
  --restart "always" \
  -e YB_ADAPTERS_IRC_USERNAME "yetibot" \
  -e YETIBOT_ADAPTERS_SLACK_TYPE "slack" \
  -e YETIBOT_ADAPTERS_SLACK_TOKEN "xoxb-123123" \
  -e YETIBOT_URL "http://..." \
  -e YETIBOT_GIPHY_KEY "" \
  -e YETIBOT_IMGFLIP_USERNAME "" \
  -e YETIBOT_IMGFLIP_PASSWORD "" \
  -e YETIBOT_EBAY_APPID "" \
  -e YETIBOT_TWITTER_CONSUMER_KEY "" \
  -e YETIBOT_TWITTER_CONSUMER_SECRET "" \
  -e YETIBOT_TWITTER_TOKEN "" \
  -e YETIBOT_TWITTER_SECRET "" \
  -e YETIBOT_TWITTER_SEARCH_LANG "" \
  -e YETIBOT_BING_SEARCH_KEY "" \
  -e YETIBOT_GOOGLE_API_KEY "" \
  -e YETIBOT_GOOGLE_CUSTOM_SEARCH_ENGINE_ID "" \
  -e YETIBOT_GOOGLE_OPTIONS_SAFE "" \
  -e YETIBOT_EVAL_PRIV_0 "" \
  -e YETIBOT_FEATURES_GITHUB_REPO "" \
  -e YETIBOT_FEATURES_GITHUB_TOKEN "" \
  -e YETIBOT_FEATURES_GITHUB_USER "" \
  -e YETIBOT_WEATHER_WUNDERGROUND_KEY "" \
  -e YETIBOT_WEATHER_WUNDERGROUND_DEFAULT_ZIP "" \
  -e YETIBOT_WOLFRAM_APPID "" \
  -e YETIBOT_WORDNIK_KEY "" \
  devth/yetibot
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

