# Versions and feature notes

```text
!version
!changelog
!changelog --since 2026-10-31 --limit 3
!changelog latest
!ai show me the new yetibot features
```

`version` reports both the app and shared core versions with source links.
Artifact versions normally use `YYYYMMDD.HHMMSS.<git-revision>`. The versions
come from the **actual classpath artifacts' Maven metadata**, not the `_`
placeholder in source `project.clj`, the website's latest release, or the
current contents of a Git checkout. Git-free source images can use their
CI-bundled manifest's app identity when Maven metadata cannot identify the
revision and its notes match. Core identity still comes from the loaded core.
Otherwise missing metadata is explicitly unknown.

Build identity and bundled notes are realized once when the release model is
loaded during startup. Updating files on disk does not change what an old JVM
reports. Restart into the new code to load its metadata and notes.

Benson's source deployment and a published artifact can have different build
timestamps and different Git abbreviation lengths for the same source revision.
A fresh `lein version` invocation describes that invocation's build, **not an
already-running service**. Compare source revisions and verify the live JVM
when qualifying a deployment; timestamps alone do not establish newer code.

## Changelog behavior

`changelog` defaults to recent user-facing features **included in the running
build**. It needs no network connection. Notes have dates, component names,
examples, prerequisites and public source links. They are not an exhaustive
Git log and do not claim every listed feature was introduced in this one build.
Provider entitlement and local configuration still determine availability.

`--since` is an inclusive ISO date; `--limit` defaults to 10 and accepts 1–20.
Responses report whether more matching notes exist and are bounded like native
help output. Normal Discord long-answer/download behavior still applies.
Structured results are available as `:result/data`; changelog collections use
`[:changes]` for pipelines. Examples in the data are documentation, not code
that is automatically executed.

`changelog latest` explicitly reads `https://yetibot.com/releases/latest.json`.
It labels the notes as **published, not proof of deployment**, and also shows
this instance's running versions. This GET uses no AI/provider credentials,
redirects or automatic retries. It has a five-second caller deadline,
connection/socket timeouts, a 512 KiB body limit and schema validation. A failed
lookup reports unavailable; it never replaces the running manifest or silently
presents bundled notes as a successful latest lookup.

## AI tool

The native read-only `release_info` capability is available by default in
trusted Slack/Discord AI requests. Existing named toolset and provider/model
policy still applies; an explicit native allowlist must include `release_info`.
It accepts only optional `view` (`running` or `latest`), `since` and `limit`.
There are no command, URL, credential, configuration-write or file-path fields.
Invalid arguments are rejected during whole-batch preflight before any tool in
that batch executes. Notes/results are marked untrusted data. Querying metadata
itself makes no inference call; asking AI to explain it uses the selected AI
provider as usual.

## Maintaining and publishing the manifest

Update `resources/yetibot/release-info.edn` with feature PRs. Add a stable unique
ID, ISO date, component (`app` or `core`), short title and summary, example chat
commands, prerequisites and public Yetibot GitHub PR/commit links. Include only
features present in the corresponding code/dependency, not planned work or
instance secrets. Keep recent entries first within a date. Tests validate the
public schema and response bounds.

`lein version` now prints the app's actual inferred version. Release automation
can emit the complete public manifest without starting adapters or resolving
provider credentials:

```sh
lein version --write target/release-info.json
# Or print the JSON:
lein version --json
```

The same build metadata and bundled notes form both the runtime manifest and
published JSON. The release workflow publishes the manifest after artifact
publication; the website exposes `/releases/latest.json` and immutable
`/releases/<version>.json` archives. Source builds may not have a matching
published timestamp; use their bundled notes rather than guessing an archive.

Schema version 1 contains only `schema_version`, `build` (`app` and `core`,
each with `version`, `revision`, `commit_url`, or null for unknown identity),
and `changes`. Query responses add view/source, counts and truncation markers;
latest queries also include `running_build` and `same_source_revisions`.
The mirror and model validate the same fields. Unknown schemas or extra fields
fail closed instead of exposing arbitrary configuration or treating feed text
as instructions.
