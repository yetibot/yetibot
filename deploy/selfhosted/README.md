# Self-hosted Yetibot (lein-run on a box)

This documents an **optional, manual** way to run Yetibot directly with
`lein run` on a single host under systemd, with a poll-based auto-updater that
tracks a git branch and redeploys when CI is green.

This is **not** the production path — production is the Docker image deployed to
Kubernetes via CI/CD. Use this for a dev/staging box (e.g. one reachable only
over a private network) where you want trunk to roll out automatically without a
container registry.

## Topology

- The bot runs as `yetibot.service` (`lein run`, `WorkingDirectory=/root/yetibot`).
- `yetibot-autoupdate.timer` fires `yetibot-autoupdate.service` every 5 minutes.
- The updater (`yetibot-autoupdate.sh`):
  1. `git ls-remote` the tracked `core` and `yetibot` refs (free, no clone).
  2. When a tracked SHA changes, queries the GitHub check-runs API and waits for
     the `test` check to be **green** (publish/deploy jobs are ignored — core is
     built from source here).
  3. Builds `yetibot/core` from source (`lein install` in `/root/core`), pins
     that version into `/root/yetibot/project.clj`, checks out the target
     `yetibot` ref, and restarts `yetibot.service`.
- Secrets (Gemini key, GitHub App key, Discord/Slack tokens, DB) live in
  `/root/yetibot/config/config.edn` on the box and are **never** committed here.

## Layout

```
deploy/selfhosted/
  provision.sh                     # installs gh + bun + gemini CLI
  yetibot-autoupdate.sh            # the poll-based updater
  yetibot-autoupdate.env.example   # updater config (copy to /root/...)
  systemd/
    yetibot.service
    yetibot-autoupdate.service
    yetibot-autoupdate.timer
    yetibot.service.d/20-gemini.conf
```

## Setup

Assumes JVM + [Leiningen](https://leiningen.org) + Postgres are already present,
the bot source is at `/root/yetibot`, and a core checkout is at `/root/core`.

```bash
# 1. Host tooling for the `agent` command (gh + bun + gemini)
sudo bash deploy/selfhosted/provision.sh

# 2. Updater config
sudo cp deploy/selfhosted/yetibot-autoupdate.env.example /root/yetibot-autoupdate.env
sudo install -m 0755 deploy/selfhosted/yetibot-autoupdate.sh /root/yetibot-autoupdate.sh
#    edit /root/yetibot-autoupdate.env to point at the refs you want to track

# 3. systemd units
sudo cp deploy/selfhosted/systemd/yetibot.service /etc/systemd/system/
sudo cp deploy/selfhosted/systemd/yetibot-autoupdate.service /etc/systemd/system/
sudo cp deploy/selfhosted/systemd/yetibot-autoupdate.timer /etc/systemd/system/
sudo mkdir -p /etc/systemd/system/yetibot.service.d
sudo cp deploy/selfhosted/systemd/yetibot.service.d/20-gemini.conf /etc/systemd/system/yetibot.service.d/

sudo systemctl daemon-reload
sudo systemctl enable --now yetibot.service yetibot-autoupdate.timer
```

## Operating

```bash
systemctl status yetibot
journalctl -u yetibot -f
tail -f /root/yetibot-autoupdate.log     # what the updater is doing
systemctl start yetibot-autoupdate.service  # force an update check now
```

To track a branch instead of trunk, set `CORE_REF` / `YETIBOT_REF` (and the
matching `*_URL` / `*_REPO` if it's on a fork) in `/root/yetibot-autoupdate.env`.

## The `agent` command

`agent <prompt>` hands the request to the Gemini CLI running headlessly, which
uses the authenticated `gh` CLI (`GH_TOKEN`) and `git` to make changes and open
PRs. That's why `provision.sh` installs gh + bun + gemini, and why the
`20-gemini.conf` drop-in sets `GEMINI_CLI_TRUST_WORKSPACE=true` (the CLI refuses
to run in an untrusted directory otherwise). Gemini + GitHub auth are configured
in `config.edn` (`:gemini :key`, `:github :app`/`:token`).
