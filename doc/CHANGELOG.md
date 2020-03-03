# Yetibot changelog

Note: `yetibot` depends on [yetibot/core](https://github.com/yetibot/yetibot.core)
which contains Yetibot's core functionality along with a few commands. See
[yetibot/core's
changelog](https://github.com/yetibot/yetibot.core/blob/master/doc/CHANGELOG.md)
as well.

## 0.5.57 - 3/2/20

- [Remove lazy json parsing in jira and add username query param to user search #1002](https://github.com/yetibot/yetibot/pull/1002)

## 0.5.56 - 3/2/20

- Upgrade to `yetibot/core "20200302.181517.2c05eb0"`. This fixes a dependency
  that caused Slack websocket connection to stop working.

## 0.5.55 - 2/27/20

- Upgrade to `yetibot/core "20200227.180915.e29cec8"`. This fixes the Slack
  adapter that was broken in `0.5.54`.

## 0.5.54 - 2/26/20

- Upgrade to `yetibot/core "20200226.184017.75c0157"`. This upgrade adds support
  for the [Mattermost](https://mattermost.org/) chat platform! 🎉🎉🎉

## 0.5.53 - 2/19/20

- Upgrade to `yetibot/core "20200219.223432.7b72073"`

## 0.5.52 - 11/11/19

- Upgrade to `yetibot/core "20191107.221752.a4aa424"`
- Upgrade all dependencies and exclude `org.flatland/useful`'s outdated
  `org.clojure/tools.reader` dep
- Add JIRA enhancements - [#981](https://github.com/yetibot/yetibot/pull/981)

  Includes:

  - Add issue type to short format
  - Support listing subtasks in `jira show <issue>`
  - Add ability to list or search for projects with `jira projects <query>`
  - Add ability to list or search for users with `jira users <query>`

## 0.5.51 - 10/29/19

- Upgrade to `yetibot/core "20191017.211644.6ee48e9"`

## 0.5.50 - 10/28/19

- Add support for oauth1 in JIRA API -
  [#974](https://github.com/yetibot/yetibot/pull/974)

## 0.5.49 - 10/17/19

- Upgrade to `yetibot/core "20191017.211644.6ee48e9"`

## 0.5.48 - 10/11/19

- Add auth when listing github topics
  [#969](Add auth when listing github topics #969)

## 0.5.47 - 10/11/19

- Upgrade to `yetibot/core "20191011.182438.972beb3"`

## 0.5.46 - 10/9/19

- Upgrade to `yetibot/core "20191009.221933.8c538c8"`

## 0.5.45 - 10/8/19

- Detect if GitHub API is enterprise and link topic search results to web search
  instead of explore topics since there is not full parity between github.com
  and GitHub Enterprise

## 0.5.44 - 10/8/19

- Fix base URL for GitHub instance when listing topics via
  `gh search topics <query>`

## 0.5.43 - 10/8/19

- Add additional github search and topics commands –
  [#964](https://github.com/yetibot/yetibot/pull/964)

  ```
  gh search repos <query> # search GitHub repos for <query>
  gh search <query> # search GitHub code for <query>
  gh search topics <query> # search GitHub topics for <query>
  gh topics <org-name>/<repo> # list topics for a repo
  gh topics set <owner>/<repo> <collection or space-separated list of topics>
  ```

## 0.5.42 - 10/7/19

- Upgrade to `yetibot/core "20191007.181520.9b061e6"`. This fixes a bug in
  monitoring that prevented Yetibot from starting up and instead erring with
  something like:

  ```
  Exception in thread "main" java.lang.IllegalArgumentException: hostname can't be null
  ```

- Add healthcheck to Dockerfile
  [#966](https://github.com/yetibot/yetibot/pull/966)

## 0.5.41 - 9/13/19

- Upgrade to `yetibot/core "20190913.182757.1838a79"`

## 0.5.40 - 9/10/19

- Upgrade to `yetibot/core "20190910.175122.9e253dd"`

## 0.5.39 - 9/10/19

- Switch from schema to clojure.spec -
  [#959](https://github.com/yetibot/yetibot/pull/959/) by
  [anthonygalea](https://github.com/anthonygalea)
- Upgrade to `yetibot/core "20190905.175835.fe16ae2"`

## 0.5.38 - 9/3/19

- Upgrade to `yetibot/core "20190903.160748.0779ab8"`

## 0.5.37 - 8/30/19

- Upgrade to `yetibot/core "20190830.225726.6817bc3"`

## 0.5.36 - 8/30/19

- Add `tldr` command - [#958](https://github.com/yetibot/yetibot/pull/958) by
  [anthonygalea](https://github.com/anthonygalea)
- Upgrade to `yetibot/core "20190830.212304.0be0d9e"`

## 0.5.35 - 8/26/19

- Add `man` command - [#957](https://github.com/yetibot/yetibot/pull/957) by
  [anthonygalea](https://github.com/anthonygalea)

## 0.5.32 - 5/17/2019

- Fixup sub command parsing in `pagerduty` so it accepts queries with whitespace
  in commands like `pd users <query>` and `pd teams <query>`
- Add better error handling in `pagerduty` for non-200 API responses

## 0.5.31 - 5/7/2019

- Upgrade to yetibot.core 0.5.21

## 0.5.30 - 4/31/2019

- Another fix for clj when data contains lazy seqs
- Security fix to prevent access of `yetibot.*` namespaces from inside the `clj`
  command

## 0.5.29 - 4/29/2019

- Fix data on clj when data is a lazy seq
  [#944](https://github.com/yetibot/yetibot/pull/944)

## 0.5.28 - 4/26/2019

- Upgrade to yetibot.core 0.5.10
- Fix Dockerfile CMD syntax [#938](https://github.com/yetibot/yetibot/pull/938)
  by [maplemuse](https://github.com/maplemuse)

## 0.5.27 - 4/18/2019

- Avoid nil description on github repos commands

## 0.5.26 - 4/17/2019

- Get extended tweets when fetching user timeline
  [#933](https://github.com/yetibot/yetibot/pull/933)

## 0.5.25 - 4/16/2019

- Upgrade to yetibot.core 0.5.19

## 0.5.24 - 4/16/2019

- Upgrade to yetibot.core 0.5.18

## 0.5.23 - 4/15/2019

- Upgrade to yetibot.core 0.5.17
- Fixup extended tweet handling in Twitter

## 0.5.22 - 4/12/2019

- Add a `cljquotes` command for spouting random quotes about Clojure -
  [#928](https://github.com/yetibot/yetibot/pull/928) by
  [justone](https://github.com/justone)

## 0.5.21 - 4/12/2019

- Upgrade to yetibot.core 0.5.16

## 0.5.20 - 4/12/2019

- Apply a new fix for `clj` by ensuring the proper `.java.policy` location when
  running in Docker

## 0.5.19 - 4/11/2019

- Fixup `clj` command to prevent security exceptions and allow access to data
  across pipes in the `clj` command
  [#926](https://github.com/yetibot/yetibot/pull/926)

## 0.5.18 - 4/10/2019

- Upgrade to yetibot.core

## 0.5.17 - 4/9/2019

- Added data and error handling to Twitter commands
- Include full text in Twitter posts instead of the abbreviated version

## 0.5.16 - 4/2/2019

Weather and JIRA improvements this release!

- Add option to specify temps in C or F on weather
  [#910](https://github.com/yetibot/yetibot/pull/910)
  by [@jcorrado](https://github.com/jcorrado)
- Fix weather parser [#907](https://github.com/yetibot/yetibot/pull/907)
  by [@jcorrado](https://github.com/jcorrado)
- Add ability to specify reporter when creating jira issues
  [#916](https://github.com/yetibot/yetibot/pull/916)
- Allow specifying issue type when creating jira issues
  [#913](https://github.com/yetibot/yetibot/pull/913)
- Add error handling when trying to create a JIRA issue without specifying a
  project and a project was not set in the channel settings
  improvements [#911](https://github.com/yetibot/yetibot/pull/911)
- Add ability to log work on JIRA issue
  [#912](https://github.com/yetibot/yetibot/pull/912)
- Upgrade to yetibot.core 0.5.13

## 0.5.15 - 3/29/2019

- Handle `429` errors from Weatherbit in the weather command
  [#904](https://github.com/yetibot/yetibot/pull/904)

## 0.5.14 - 3/28/2019

- Upgrade to yetibot.core 0.5.12
- Add `weather forecast` support
  [#901](https://github.com/yetibot/yetibot/pull/901)
- Use auth on GH releases API calls

## 0.5.13 - 3/25/2019

- Add auth to GitHub release API calls

## 0.5.12 - 3/25/2019

- Add `data` and error handling support on GitHub commands
- Allow flexible help and command prefixes for GitHub: `gh` or `github`
- Remove `gh repos urls` command - use `data` instead!
  ```
  !gh repos | data $.[*].ssh_url
  ```
- Rename `gh statuses` to `gh incidents`

## 0.5.11 - 3/21/2019

- Upgrade to yetibot.core 0.5.11

## 0.5.10 - 3/19/2019

- Upgrade to yetibot.core 0.5.10

## 0.5.9 - 3/19/2019

- Upgrade to yetibot.core 0.5.9

## 0.5.8 - 3/14/2019

- Fix missing cheshire dep

## 0.5.7 - 3/14/2019

Botched release - use 0.5.8 instead.

- Upgrade to yetibot.core 0.5.8
- Ignored .dumbjump for Emacs' dumb-jump

## 0.5.6 - 3/4/2019

- Upgrade to yetibot.core 0.5.7
- Move karma to yetibot.core -
  [#856](https://github.com/yetibot/yetibot/pull/856) by
  [@jcorrado](https://github.com/jcorrado)

## 0.5.5 - 3/3/2019

- Upgrade to yetibot.core 0.5.6

## 0.5.4 - 3/1/2019

- Allow meme search to find PNGs in addition to JPGs on imgflip.com

## 0.5.3 - 3/1/2019

- Upgrade to yetibot.core 0.5.5

## 0.5.2 - 3/1/2019

- Move `repeat` to `yetibot.core` collections
- Upgrade to yetibot.core 0.5.3

## 0.5.1 - 2/27/2019

- Upgrade to yetibot.core 0.5.2

## 0.5.0 - 2/24/2019

This is the release where we deprecate mutable config! See [the blog
post](https://yetibot.com/blog/2019-02-20-moving-mutable-config-to-the-database).

- Upgrade to yetibot.core 0.5.0
- Refresh the `no` gif pool

## 0.4.80 - 2/18/2019

- Relax schema on jira config

## 0.4.79 - 2/18/2019

- Ensure `json <path>` always return a string for individual values.
  [#829](https://github.com/yetibot/yetibot/issues/829).
- Add data and error handling to jira
- Trim symbol in `stock` command
- Upgrade to yetibot.core 0.4.67

## 0.4.78 - 2/4/2019

- Fix bug in `json <url>` command where parsing a URL did not parse into Clojure
  keywords the same way that `json parse` does

## 0.4.77 - 1/18/2019

- Upgrade to yetibot.core 0.4.66

## 0.4.76 - 1/17/2019

- Upgrade to yetibot.core 0.4.65

## 0.4.75 - 1/17/2019

- Upgrade to yetibot.core 0.4.64

## 0.4.74 - 1/7/2019

- Upgrade to Clojure 1.10 -
  [#809](https://github.com/yetibot/yetibot/pull/809)
- Upgrade to yetibot.core 0.4.63
- Fix up the `scala` command -
  [#802](https://github.com/yetibot/yetibot/pull/802) by
  [kaffein](https://github.com/kaffein)
  This hits a new endpoint at `https://scastie.scala-lang.org/api` and uses
  Server-Sent Events with `core.async` to obtain the evaluation result!

## 0.4.73 - 12/12/2018

- Upgrade to yetibot.core 0.4.62

## 0.4.72 - 12/12/2018

- Upgrade to yetibot.core 0.4.61

## 0.4.71 - 12/10/2018

- Upgrade to yetibot.core 0.4.60
- Add proper data and error handling to meme command

## 0.4.70 - 12/6/2018

- Upgrade to yetibot.core 0.4.59

## 0.4.69 - 12/5/2018

- Switch back to `clojure:lein-2.8.1` Docker image to support curl

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


