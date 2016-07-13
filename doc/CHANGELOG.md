# yetibot changelog

## 1.0.0

Yetibot 1.0 is here! 1.0 brings non-backward compatible changes.

### Non-backward Compatible Changes

- *Config*: main config is now immutable, and can be provided in
  12-Factor-compatible methods, such as env-vars. As a result, is it also flat
  KV pairs now, which are exploded into nested maps by
  [dec](https://github.com/devth/dec).

  - new [profiles.sample.clj](https://github.com/devth/yetibot.core/blob/master/profiles.sample.clj)
  - new [Configuration docs](https://github.com/devth/yetibot.core/blob/master/docs/CONFIGURATION.md)

- *Mutable config*: mutable config, such as which IRC rooms to join and
  channel-specific settings has been extracted into a separate file that is
  managed by Yetibot.

### Removed

- Remove all uses of `config-for-ns` - this was never a good idea.

- Removed Jenkins `add` and `remove` commands in favor of immutable
  configuration and simplified code

## 0.1.74

- upgrade yetibot.core to 0.3.14

## 0.1.73

- remove tentacles dependency - put it in yetibot.core instead

## 0.1.70

- fix mapping bug when using `gh repos`

## 0.1.69

- fix syntax error on giftv command

## 0.1.68

- upgrade to yetibot.core 0.3.8

## 0.1.67

- fix src copy in Dockerfile

## 0.1.66

- add specific $version to Docker tag

## 0.1.65

- upgrade Docker engine in Travis to attempt to fix docker.sh latest tagging

## 0.1.64

- auto-deploy to Docker Hub. See
  [DOCKER](https://github.com/devth/yetibot/blob/master/doc/DOCKER.md) for usage
  docs.

## 0.1.63

- upgrade to Clojure 1.7

## 0.1.62

- begin categorizing commands, especially `:fun` so that they can be optionally
  disabled in serious/no-fun/work channels 👮👮👮

  See [yetibot.core release
  notes](https://github.com/devth/yetibot.core/blob/master/doc/CHANGELOG.md#033)
  for more info on category support.


