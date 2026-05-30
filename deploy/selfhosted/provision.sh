#!/usr/bin/env bash
# Provision the host tooling a self-hosted lein-run Yetibot needs, beyond the
# JVM + Leiningen + Postgres you supply separately:
#
#   - gh         GitHub CLI (used by the `agent` command's Gemini runs)
#   - bun        JS runtime used to install + run the Gemini CLI
#   - gemini     Google Gemini CLI (the `agent` command shells out to it)
#
# Idempotent: re-running upgrades/repairs in place. Run as root.
set -euo pipefail
export PATH=/usr/local/bin:/usr/bin:/bin:/root/.bun/bin
export BUN_INSTALL=/root/.bun

echo "==> gh CLI"
if ! command -v gh >/dev/null 2>&1; then
  arch=$(uname -m); case "$arch" in x86_64) a=amd64;; aarch64|arm64) a=arm64;; *) a=amd64;; esac
  ver=$(curl -fsSL https://api.github.com/repos/cli/cli/releases/latest \
        | python3 -c 'import sys,json;print(json.load(sys.stdin)["tag_name"].lstrip("v"))')
  curl -fsSL "https://github.com/cli/cli/releases/download/v${ver}/gh_${ver}_linux_${a}.tar.gz" -o /tmp/gh.tgz
  tar xzf /tmp/gh.tgz -C /tmp
  install -m 0755 "/tmp/gh_${ver}_linux_${a}/bin/gh" /usr/local/bin/gh
  rm -rf /tmp/gh.tgz "/tmp/gh_${ver}_linux_${a}"
fi
gh --version | head -1

echo "==> bun"
if ! command -v bun >/dev/null 2>&1; then
  curl -fsSL https://bun.sh/install | bash
fi
ln -sf /root/.bun/bin/bun /usr/local/bin/bun
echo "bun $(bun --version)"

echo "==> Gemini CLI (installed + run under bun)"
bun install -g @google/gemini-cli
ENTRY=/root/.bun/install/global/node_modules/@google/gemini-cli/bundle/gemini.js
cat > /usr/local/bin/gemini <<EOF
#!/usr/bin/env bash
exec /usr/local/bin/bun "$ENTRY" "\$@"
EOF
chmod 0755 /usr/local/bin/gemini
gemini --version | tail -1

echo "==> done. gh + bun + gemini are on PATH for the systemd service."
