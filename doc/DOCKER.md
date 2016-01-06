# Docker

The official Yetibot image is
[devth/yetibot](https://hub.docker.com/r/devth/yetibot/). It's built on the
official [Clojure image](https://hub.docker.com/_/clojure/).

## Configuration

Configuration must be passed in using the volume at `/usr/src/app/config`. The
mounted directory must contain a file `config.edn` as described by the [sample
config](https://github.com/devth/yetibot/blob/master/config/config-sample.edn).

## Ports

Yetibot runs a webapp on port `3000`. You may optionally expose it via `-P` or
`-p` to choose your own host port mapping.

## Running

Start up Yetibot in detached mode with port 3000 mapped:

```
# path to your config directory containing config.edn
YB_CONFIG_PATH=...

docker run --name yetibot \
  -d -p 3000:3000 \
  -v $YB_CONFIG_PATH:/usr/src/app/config \
  devth/yetibot
```

<em>Note, if you're using Docker Machine, you can view the webapp at its IP
rather than localhost.</em>

Tail its logs:

```
docker logs -f yetibot
```

## Troubleshoting

To run an ephemeral interactive shell and poke around instead of running Yetibot:

```
docker run --rm -it --name yetibot \
  -v $YB_CONFIG_PATH:/usr/src/app/config \
  devth/yetibot \
  /bin/bash
```

