#!/usr/bin/env python3
"""Reuse PR tests only when they ran on the exact tree being released.

test.yml checks out the PR HEAD explicitly, not GitHub's synthetic merge.
A different merged tree, failed/pending check or API error requires full tests.
Keep this helper in sync between yetibot/core and yetibot/yetibot.
"""
import json
import os
import re
import subprocess


def reusable(api, sha):
  commit = api(f"git/commits/{sha}")
  for pr in api(f"commits/{sha}/pulls?per_page=20"):
    if not pr.get("merged_at") or pr.get("merge_commit_sha") != sha:
      continue
    head = pr["head"]["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", head):
      continue
    if api(f"git/commits/{head}")["tree"]["sha"] != commit["tree"]["sha"]:
      continue
    runs = api("actions/workflows/test.yml/runs?"
               f"event=pull_request&head_sha={head}&per_page=5")["workflow_runs"]
    if not runs:
      continue
    # A newer failed/pending rerun must not be hidden by an older green run.
    run = max(runs, key=lambda r: (r["updated_at"], r["id"]))
    if not (run["head_sha"] == head and run["event"] == "pull_request"
            and run["path"] == ".github/workflows/test.yml"
            and run["status"] == "completed"
            and run["conclusion"] == "success"):
      continue
    jobs = api(f"actions/runs/{run['id']}/jobs?filter=latest")["jobs"]
    tests = [j for j in jobs if j["name"] == "test"]
    if len(tests) == 1 and tests[0]["conclusion"] == "success":
      return run["html_url"]
  return None


def main():
  repo = os.environ["GITHUB_REPOSITORY"]
  sha = os.environ["GITHUB_SHA"]

  def api(path):
    return json.loads(subprocess.check_output(
      ["gh", "api", f"repos/{repo}/{path}"],
      stderr=subprocess.DEVNULL, timeout=20))

  try:
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
      raise ValueError("Invalid commit")
    proof = reusable(api, sha)
  except (subprocess.SubprocessError, ValueError, KeyError, TypeError):
    proof = None
  value = "true" if proof else "false"
  if os.environ.get("GITHUB_OUTPUT"):
    with open(os.environ["GITHUB_OUTPUT"], "a") as out:
      out.write(f"reuse={value}\n")
  print(f"Reuse exact-tree PR tests: {proof}" if proof
        else "No reusable PR validation; running the full test suite.")


if __name__ == "__main__":
  main()
