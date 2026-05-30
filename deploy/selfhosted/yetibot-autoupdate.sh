#!/usr/bin/env bash
# Auto-update the lein-run yetibot instance when a tracked ref's CI is green.
# Tracks yetibot/core and yetibot/yetibot at refs configured in the env file
# (default: master). SHA detection uses git fetch (free); the GitHub check-runs
# REST API is only queried when a ref's head SHA changes, so steady-state API
# usage is ~0 and stays well under the unauthenticated 60 req/hour limit.

set -uo pipefail

ENV_FILE=/root/yetibot-autoupdate.env
STATE_FILE=/root/.yetibot-autoupdate.state
LOCK_FILE=/root/.yetibot-autoupdate.lock
CORE_DIR=/root/core
YB_DIR=/root/yetibot
LOG=/root/yetibot-autoupdate.log

exec >>"$LOG" 2>&1
exec 9>"$LOCK_FILE"
flock -n 9 || { echo "$(date -u +%FT%TZ) lock held, skipping"; exit 0; }

log() { echo "$(date -u +%FT%TZ) $*"; }

CORE_REF=master
YETIBOT_REF=master
CORE_URL=https://github.com/yetibot/core.git
YB_URL=https://github.com/yetibot/yetibot.git
# owner/repo to query for CI status (set to a fork when tracking its branch)
CORE_REPO=yetibot/core
YB_REPO=yetibot/yetibot
REQUIRED_CHECK=test
DRY_RUN=0
GITHUB_TOKEN=""
# shellcheck disable=SC1090
[ -f "$ENV_FILE" ] && . "$ENV_FILE"

AUTH=()
[ -n "$GITHUB_TOKEN" ] && AUTH=(-H "Authorization: Bearer $GITHUB_TOKEN")

CORE_DEPLOYED=""
YB_DEPLOYED=""
CORE_VERSION=""
# shellcheck disable=SC1090
[ -f "$STATE_FILE" ] && . "$STATE_FILE"

remote_sha() { # <url> <ref> -> prints head sha; ls-remote over HTTPS (no creds)
  git ls-remote "$1" "$2" 2>/dev/null | awk 'NR==1 {print $1}'
}

ci_state() { # <owner/repo> <sha> -> prints green|pending|failed|none|error
  # Gates on the test check(s) only (REQUIRED_CHECK). Publish/deploy jobs are
  # ignored: the bot builds core from source, so a cancelled/failed Clojars
  # deploy must not block updates.
  local json
  json=$(curl -fsSL --max-time 30 "${AUTH[@]}" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$1/commits/$2/check-runs" 2>/dev/null) || { echo error; return; }
  printf '%s' "$json" | REQUIRED_CHECK="$REQUIRED_CHECK" python3 -c '
import json,os,sys
req=os.environ.get("REQUIRED_CHECK","test")
d=json.load(sys.stdin)
runs=[r for r in d.get("check_runs",[]) if r.get("name")==req]
if not runs:
    print("none"); raise SystemExit
if any(r.get("status")!="completed" for r in runs):
    print("pending"); raise SystemExit
if any((r.get("conclusion") or "")!="success" for r in runs):
    print("failed"); raise SystemExit
print("green")
' 2>/dev/null || echo error
}

write_state() {
  cat >"$STATE_FILE" <<EOF
CORE_DEPLOYED=$1
YB_DEPLOYED=$2
CORE_VERSION=$3
EOF
}

log "checking core@$CORE_REF yetibot@$YETIBOT_REF (dry_run=$DRY_RUN)"

core_sha=$(remote_sha "$CORE_URL" "$CORE_REF")
yb_sha=$(remote_sha "$YB_URL" "$YETIBOT_REF")
[ -n "$core_sha" ] || { log "ERROR: could not resolve core@$CORE_REF from $CORE_URL"; exit 1; }
[ -n "$yb_sha" ]   || { log "ERROR: could not resolve yetibot@$YETIBOT_REF from $YB_URL"; exit 1; }

target_core_sha=$CORE_DEPLOYED
target_yb_sha=$YB_DEPLOYED
core_rebuilt=0
deploy_yb=0

# --- core ---
if [ "$core_sha" != "$CORE_DEPLOYED" ]; then
  st=$(ci_state "$CORE_REPO" "$core_sha")
  log "core $core_sha ci=$st"
  if [ "$st" = green ]; then
    if [ "$DRY_RUN" = 1 ]; then
      log "[dry-run] would build+install core@$core_sha"
      target_core_sha=$core_sha
    else
      log "building core@$core_sha"
      git -C "$CORE_DIR" fetch -q "$CORE_URL" "$CORE_REF"
      git -C "$CORE_DIR" checkout -q -f "$core_sha"
      if (cd "$CORE_DIR" && lein install) >/tmp/yetibot-core-install.log 2>&1; then
        ver=$(grep -oE '/target/core-[0-9][^ ]*\.jar' /tmp/yetibot-core-install.log | head -1 | sed -E 's#.*/core-(.*)\.jar$#\1#')
        if [ -n "$ver" ]; then
          CORE_VERSION=$ver
          target_core_sha=$core_sha
          core_rebuilt=1
          log "installed core $CORE_VERSION"
        else
          log "ERROR: could not parse installed core version"
        fi
      else
        log "ERROR: core lein install failed (see /tmp/yetibot-core-install.log)"
      fi
    fi
  fi
fi

# --- yetibot ---
if [ "$yb_sha" != "$YB_DEPLOYED" ]; then
  st=$(ci_state "$YB_REPO" "$yb_sha")
  log "yetibot $yb_sha ci=$st"
  if [ "$st" = green ]; then
    deploy_yb=1
    target_yb_sha=$yb_sha
  fi
fi

if [ "$DRY_RUN" = 1 ]; then
  log "[dry-run] core_rebuilt=$core_rebuilt deploy_yb=$deploy_yb -> done"
  exit 0
fi

if [ "$core_rebuilt" = 0 ] && [ "$deploy_yb" = 0 ]; then
  log "nothing to deploy"
  exit 0
fi

# Need a core version to pin against.
if [ -z "$CORE_VERSION" ]; then
  log "ERROR: no core version available to pin; aborting"
  exit 1
fi

# Check out the target yetibot ref cleanly (discarding the local dep pin), then
# re-pin the core dependency to the locally built version and restart.
git -C "$YB_DIR" fetch -q "$YB_URL" "$YETIBOT_REF"
git -C "$YB_DIR" checkout -q -f "$target_yb_sha"
( cd "$YB_DIR" && lein update-dependency yetibot/core "$CORE_VERSION" ) >/tmp/yetibot-pin.log 2>&1 \
  || { log "ERROR: lein update-dependency failed (see /tmp/yetibot-pin.log)"; exit 1; }
log "pinned yetibot/core -> $CORE_VERSION on yetibot@$target_yb_sha"

if systemctl restart yetibot; then
  write_state "$target_core_sha" "$target_yb_sha" "$CORE_VERSION"
  log "restarted yetibot (core=$CORE_VERSION yetibot=$target_yb_sha)"
else
  log "ERROR: systemctl restart yetibot failed"
  exit 1
fi
