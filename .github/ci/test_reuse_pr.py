import copy
import unittest
from reuse_pr import reusable

SHA = "a" * 40
HEAD = "b" * 40
RUN = {"id": 10, "head_sha": HEAD, "event": "pull_request",
       "path": ".github/workflows/test.yml", "status": "completed",
       "conclusion": "success", "updated_at": "2026-11-03T00:00:00Z",
       "html_url": "https://github.com/example/project/actions/runs/10"}


class ReuseTests(unittest.TestCase):
  def setUp(self):
    self.data = {
      f"git/commits/{SHA}": {"tree": {"sha": "same-tree"}},
      f"git/commits/{HEAD}": {"tree": {"sha": "same-tree"}},
      f"commits/{SHA}/pulls?per_page=20":
        [{"merged_at": "today", "merge_commit_sha": SHA,
          "head": {"sha": HEAD}}],
      "actions/workflows/test.yml/runs?"
      f"event=pull_request&head_sha={HEAD}&per_page=5":
        {"workflow_runs": [copy.deepcopy(RUN)]},
      "actions/runs/10/jobs?filter=latest":
        {"jobs": [{"name": "test", "conclusion": "success"}]}}

  def proof(self):
    return reusable(self.data.__getitem__, SHA)

  def test_identical_squash_tree_reuses_successful_pr(self):
    self.assertEqual(self.proof(), RUN["html_url"])

  def test_changed_base_or_direct_push_requires_tests(self):
    self.data[f"git/commits/{SHA}"]["tree"]["sha"] = "new-tree"
    self.assertIsNone(self.proof())
    self.data[f"commits/{SHA}/pulls?per_page=20"] = []
    self.assertIsNone(self.proof())

  def test_run_must_be_latest_successful_pr_head_workflow(self):
    key = ("actions/workflows/test.yml/runs?"
           f"event=pull_request&head_sha={HEAD}&per_page=5")
    for field, value in [("status", "in_progress"),
                         ("conclusion", "failure"), ("event", "push"),
                         ("head_sha", "c" * 40), ("path", "other.yml")]:
      with self.subTest(field=field):
        self.data[key]["workflow_runs"] = [dict(RUN, **{field: value})]
        self.assertIsNone(self.proof())
    self.data[key]["workflow_runs"] = [
      RUN, dict(RUN, id=11, conclusion="failure",
                updated_at="2026-11-03T00:01:00Z")]
    self.assertIsNone(self.proof())

  def test_skipped_missing_or_failed_test_job_cannot_pass(self):
    for jobs in [[], [{"name": "test", "conclusion": "skipped"}],
                 [{"name": "test", "conclusion": "failure"}]]:
      self.data["actions/runs/10/jobs?filter=latest"]["jobs"] = jobs
      self.assertIsNone(self.proof())


if __name__ == "__main__":
  unittest.main()
