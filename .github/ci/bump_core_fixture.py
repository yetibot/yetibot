#!/usr/bin/env python3
"""Qualify the real published core, then update its strict identity fixture."""
import concurrent.futures
import hashlib
import io
import json
import os
import pathlib
import re
import urllib.request
import zipfile

SOURCES = [
  "adapters/discord.clj", "adapters/discord_components.clj",
  "adapters/discord_inspection.clj", "util/public_result.clj",
  "chat.clj", "commands/thread.clj", "interpreter.clj", "handler.clj"]


def fetch(url, limit):
  with urllib.request.urlopen(url, timeout=30) as response:
    data = response.read(limit + 1)
  if len(data) > limit:
    raise ValueError("Published resource exceeds its bound")
  return data


def fixture(text, version, digest):
  replacements = [
    (r"/yetibot/core/[^/\s]+/", f"/yetibot/core/{version}/"),
    (r"core-[^\s\"]+\.jar", f"core-{version}.jar"),
    (r'=> "[a-f0-9]{64}"', f'=> "{digest}"')]
  for pattern, value in replacements:
    text, count = re.subn(pattern, lambda _: value, text)
    if count != 1:
      raise ValueError("Strict core identity fixture no longer has its expected shape")
  return text


def main():
  version = os.environ["YB_VERSION"]
  if not re.fullmatch(r"[0-9]{8}\.[0-9]{6}\.[a-f0-9]{7,40}", version):
    raise ValueError("Invalid published core version")
  revision = version.rsplit(".", 1)[1]
  commit = json.loads(fetch(
    f"https://api.github.com/repos/yetibot/core/commits/{revision}", 2000000))["sha"]
  if not re.fullmatch(r"[a-f0-9]{40}", commit) or not commit.startswith(revision):
    raise ValueError("Published core revision did not resolve")
  data = fetch(f"https://repo.clojars.org/yetibot/core/{version}/"
               f"core-{version}.jar", 32 * 1024 * 1024)

  def source(name):
    return fetch(f"https://raw.githubusercontent.com/yetibot/core/{commit}/"
                 f"src/yetibot/core/{name}", 512 * 1024)

  with zipfile.ZipFile(io.BytesIO(data)) as jar:
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
      for name, expected in zip(SOURCES, pool.map(source, SOURCES)):
        member = jar.getinfo("yetibot/core/" + name)
        if member.file_size > 512 * 1024 or jar.read(member) != expected:
          raise ValueError("Published core differs from its committed source")
    digest = hashlib.sha256(
      jar.read("yetibot/core/adapters/discord.clj")).hexdigest()
  path = pathlib.Path("test/yetibot/test/integration/ai_status.clj")
  path.write_text(fixture(path.read_text(), version, digest))
  print(f"Qualified genuine core {version}: eight sources match {commit}; "
        f"JAR SHA256 {hashlib.sha256(data).hexdigest()}")


if __name__ == "__main__":
  main()
