import pathlib
import unittest
from bump_core_fixture import fixture


class FixtureTests(unittest.TestCase):
  def test_updates_identity_without_weakening_assertions(self):
    path = pathlib.Path("test/yetibot/test/integration/ai_status.clj")
    text = path.read_text()
    updated = fixture(text, "20261103.000000.abcdef0", "a" * 64)
    self.assertIn("/yetibot/core/20261103.000000.abcdef0/", updated)
    self.assertIn("core-20261103.000000.abcdef0.jar", updated)
    self.assertIn('=> "' + "a" * 64 + '"', updated)
    self.assertIn('(.getProtocol resource) => "jar"', updated)
    self.assertIn('(.getProtocol jar-url) => "file"', updated)
    self.assertIn('(.getEntryName connection) => "yetibot/core/adapters/discord.clj"',
                  updated)

  def test_changed_fixture_shape_fails_closed(self):
    with self.assertRaises(ValueError):
      fixture("no identity assertions here", "v", "a" * 64)


if __name__ == "__main__":
  unittest.main()
