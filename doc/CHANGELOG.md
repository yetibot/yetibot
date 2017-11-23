# Yetibot changelog

## 0.4.16

- Upgrade to `yetibot.core 0.4.16`

## 0.4.15

- Upgrade to `yetibot.core 0.4.15`
- New `base64` command. [#688](https://github.com/yetibot/yetibot/pull/688) by
  [@themistoklik](https://github.com/themistoklik)

## 0.4.14

- Upgrade to `yetibot.core 0.4.14`

## 0.4.13

- Upgrade to `yetibot.core 0.4.13`
- Fix the stock command

## 0.4.12

- Upgrade to `yetibot.core 0.4.12`

## 0.4.11

- Upgrade to `yetibot.core 0.4.11`
- Fix meme search scraper on imgflip -
  [#684](https://github.com/yetibot/yetibot/issues/684)

## 0.4.10

- Upgrade to `yetibot.core 0.4.10`
- Fix catfact command - [#677](https://github.com/yetibot/yetibot/pull/677) by
  [@brstf](https://github.com/brstf)
- Upgrade all other deps to latest

## 0.4.9

- Upgrade to `yetibot.core 0.4.9`

## 0.4.8

- Fix a json parsing error - [#620](https://github.com/devth/yetibot/pull/647)
  by [@jkieberk](https://github.com/jkieberk)
- Add `emoji` command - [#651](https://github.com/devth/yetibot/pull/651) by
  [@jkieberk](https://github.com/jkieberk)
- Improve meme searching by always scraping.
  [#646](https://github.com/devth/yetibot/pull/646?F) by
  [@jkieberk](https://github.com/jkieberk)

## 0.4.7

- Fixup linting issues for markdown, shell and Clojure
- Upgrade to yetibot.core 0.4.7 for fixes on creating mutable config when not
  present

## 0.4.6

- Renamed `react` command to `replygif` to make way for Slack `react` command.
  This might be removed in the future since it's easily aliasable with `scrape`.
- Moved `nil` to `yetibot.core`
- Upgrade to `yetibot.core "0.4.6"`

## 0.4.5

- Upgrade to `yetibot.core "0.4.5"`

## 0.4.4

- Upgrade to `yetibot.core "0.4.4"`
- Add `gh contributors since` to show contribution stats on a repo since given
  date/time - [#595](https://github.com/devth/yetibot/issues/595)

## 0.4.3

- Use Chrome user agent for scrape - [#604](https://github.com/devth/yetibot/issues/604)
- Upgrade to `yetibot.core "0.4.3"`

## 0.4.2

### Added

- `json` command to parse json from text or from a url
  [#601](https://github.com/devth/yetibot/issues/601)
- `json path` subcommand to select from data structures via JsonPath

## 0.4.1

### Added

- `scrape` command [#310](https://github.com/devth/yetibot/issues/310)
- `meme popular` in terms of `scrape`

## 0.4.0

0.4.0 brings non-backward compatible changes, particularly around configuration
refactoring.

### Non-backward Compatible Changes

- See [yetibot.core
  CHANGELOG](https://github.com/devth/yetibot.core/blob/master/doc/CHANGELOG.md#040)
  for info on configuration changes.

- Upgraded to Clojure 1.8.0

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


