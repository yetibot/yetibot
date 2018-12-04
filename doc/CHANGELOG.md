# Yetibot changelog

Note: yetibot depends on [yetibot.core](https://github.com/yetibot/yetibot.core)
which contains Yetibot's core functionality along with a few commands. See
[yetibot.core's
changelog](https://github.com/yetibot/yetibot.core/blob/master/doc/CHANGELOG.md)
as well.

## 0.4.68 - 12/4/2018

- Fixup tests that failed the last release

## 0.4.67 - 12/4/2018

- Upgrade to yetibot.core 0.4.58
- Populate data for weather commands -
  [#708](https://github.com/yetibot/yetibot/issues/708)

## 0.4.66 - 12/4/2018

- Accidental release containing no changes

## 0.4.65 - 12/3/2018

- Upgrade to yetibot.core 0.4.57

## 0.4.64 - 12/1/2018

- Fixup `repeat`

## 0.4.63 - 12/1/2018

- Fix bug in `memethat` where it would always return a `No history to meme`
  error
- Add `result/data`, `result/value` and `result/error` to `memethat`

## 0.4.62 - 11/30/2018

- Switch to `clojure:lein-alpine` base image

## 0.4.61 - 11/30/2018

- Pagerduty support - [#795](https://github.com/yetibot/yetibot/pull/795)
- Upgrade to yetibot.core 0.4.56: this fixes an issue where Yetibot would
  double-record anything that Slack unfurls, because it was firing a
  message-changed event. Now we ignore message-change events from the Yetibot
  user.
- Properly record history from !memethat and fix multiple !memethat invocations
  as a side effect of the above fix (not double recording anymore)

## 0.4.60 - 11/16/2018

- Tighter parsing on `karma` -
  [#793](https://github.com/yetibot/yetibot/pull/793) by
  [@jcorrado](https://github.com/jcorrado)
- Upgrade to yetibot.core 0.4.54

## 0.4.59 - 11/5/2018

- Karma `@username` support POC: manifest as output formatter -
  [#781](https://github.com/yetibot/yetibot/pull/781) by
  [@jcorrado](https://github.com/jcorrado)

## 0.4.58 - 11/4/2018

- Karma leaderboard should not including scores of 0 or less -
  [#779](https://github.com/yetibot/yetibot/pull/779) by
  [@jcorrado](https://github.com/jcorrado)

## 0.4.57 - 11/4/2018

- Upgrade to yetibot.core 0.4.52
- Add `github release` subcommands to for listing releases and fetching the
  release info for a given repo -
  [#772](https://github.com/yetibot/yetibot/pull/772) by
  [kaffein](https://github.com/kaffein)
- `karma` command - [#774](https://github.com/yetibot/yetibot/pull/774) by
  [@jcorrado](https://github.com/jcorrado)

## 0.4.56 - 10/29/2018

- Upgrade to yetibot.core 0.4.48 for xargs bugfix

## 0.4.55 - 10/29/2018

- Upgrade to yetibot.core 0.4.47
- Fixup pirate punctuation -
  [#770](https://github.com/yetibot/yetibot/pull/770) by
  [@jcorrado](https://github.com/jcorrado)
- Add optional `<name>` parameter to `chuck` command

## 0.4.54 - 10/23/2018

- Migrate `weather` from Weather Underground to Weatherbit.io -
  [#763](https://github.com/yetibot/yetibot/pull/763) by
  [@jcorrado](https://github.com/jcorrado)
- Upgrade to yetibot.core 0.4.46

## 0.4.53 - 10/20/2018

- Upgrade to yetibot.core 0.4.45

## 0.4.52 - 10/17/2018

- Add postal code model for parsing postal codes with support for AU, BR, GB,
  NL, PH, RO, and US [#755](https://github.com/yetibot/yetibot/pull/755) by
  [@jcorrado](https://github.com/jcorrado)
- Dry out pirate slightly: reduces the chance it'll slur and only slurs 1
  letter per word [#758](https://github.com/yetibot/yetibot/pull/758) by
  [@jcorrado](https://github.com/jcorrado)

## 0.4.51 - 10/16/2018

- Upgrade to yetibot.core 0.4.44

## 0.4.50 - 10/15/2018

- Add `./resources/` to the Dockerfile

## 0.4.49 - 10/15/2018

- Add `:resource-paths ["resources"]` config to fix
  [#752](https://github.com/yetibot/yetibot/issues/752)

## 0.4.48 - 10/15/2018

- `pirate` command [#747](https://github.com/yetibot/yetibot/pull/747/)
   by [@jcorrado](https://github.com/jcorrado)
   <img src="http://i.imgflip.com/2k64lz.jpg" />

   :100:

- Upgrade to yetibot.core 0.4.43

## 0.4.47 - 10/13/2018

- Fix Docker Compose env var Slack example
  [#738](https://github.com/yetibot/yetibot/pull/738) by [@jcorrado](https://github.com/jcorrado)
- Upgrade dependencies [#746](https://github.com/yetibot/yetibot/pull/746) by
  [@linuxsoares](https://github.com/linuxsoares)
- Upgrade to yetibot.core 0.4.42

## 0.4.46 - 9/28/2018

- Upgrade to yetibot.core 0.4.41

## 0.4.45 - 9/24/2018

- Upgrade to yetibot.core 0.4.40

## 0.4.44 - 9/5/2018

- Add `gcs` command to list and view the contents of Google Cloud Storage
  buckets

## 0.4.43 - 6/29/2018

- Update getting started docs with Docker Compose example and update
  docker-compose.yml manifest
- Upgrade to yetibot.core 0.4.39

## 0.4.42 - 6/24/2018

- Upgrade to yetibot.core 0.4.38

## 0.4.41 - 6/23/2018

- Upgrade to yetibot.core 0.4.36

## 0.4.40 - 6/23/2018

- Upgrade to yetibot.core 0.4.35 for improved GraphQL coverage and dashboard

## 0.4.39 - 5/29/2018

- Upgrade to yetibot.core 0.4.34

## 0.4.38 - 5/9/2018

- Upgrade to yetibot.core 0.4.33

## 0.4.37 - 5/9/2018

- Upgrade to yetibot.core 0.4.32

## 0.4.36 - 4/26/18

- Fix catfact (again) by switching to catfact.ninja
- Upgrade to yetibot.core 0.4.31

## 0.4.35 - 4/26/18

- Upgrade to yetibot.core 0.4.30

## 0.4.34 - 4/20/18

- Add initial [Catchpoint](http://www.catchpoint.com/) support

## 0.4.33 - 3/28/18

- Upgrade to yetibot.core 0.4.29

## 0.4.32 - 3/28/18

- Upgrade to yetibot.core 0.4.28

## 0.4.31

- Apply a new fix to actually fix `clj` when running inside Docker on Linux. For
  some reason it worked inside Docker on Docker for Mac. 🤔

  Fix is in
  [9c36f75](https://github.com/yetibot/yetibot/commit/9c36f756becd2bebcf6923c4f6fc428e40163f8f)

## 0.4.30

- Upgrade to yetibot.core 0.4.27

## 0.4.29

- Fix broken `clj` command when running inside Docker container -
  [#716](https://github.com/yetibot/yetibot/issues/716)

## 0.4.28

- Upgrade to yetibot.core 0.4.26

## 0.4.27

- Upgrade to yetibot.core 0.4.25

## 0.4.26

- Upgrade to yetibot.core 0.4.24

## 0.4.25

- Fix bug where `memethat` used the wrong history item

## 0.4.24

- Fix bug in `!memethat` command

## 0.4.23

- Upgrade to yetibot.core 0.4.23

## 0.4.22

- No changes / accidental release

## 0.4.21

- Upgrade to yetibot.core0.4.20

## 0.4.20

- Upgrade to Clojure 1.9!
- Upgrade to yetibot.core 0.4.19
- Fix ascii command - [#518](https://github.com/yetibot/yetibot/issues/518)

## 0.4.19

- Fix XML parsing issue in `complete` by switching to `clojure.data.xml`

## 0.4.18

- Fix docker script in CI

## 0.4.17

- Upgrade to `yetibot.core 0.4.17`
- Migrate from Datomic to Postgres
- Update stock command to use the very nice [IEX
  API](https://iextrading.com/developer/docs)

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


