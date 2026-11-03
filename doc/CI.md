# PR-first CI

The full test suite runs once per PR head, not on both push and pull request.
New commits cancel obsolete PR runs. Tests check out the exact PR head;
required `test` checks keep their existing name.

PRs run Midje without coverage instrumentation. Coverage was not being
uploaded; it is not a shipping gate. Both catalog-cache clojure.test suites also remain mandatory.

On master, the release's `test` job reuses successful PR validation only when
the PR head tree exactly equals the merged commit tree and the latest Test
workflow and its actual test job succeeded. Changed integration trees,
direct pushes, missing checks and API failures run the full suite instead.
The release depends on this gate; Benson can keep requiring `test` without
any protection or updater changes.

The cheap Python regression tests cover reuse and rejection. Run locally:

```sh
python3 -m unittest discover -s .github/ci -v
```

Do targeted local checks while developing; let PR CI own full-suite validation.
Do not repeat an unchanged successful full suite merely to commit or merge.
Privileged publication and auto-merge workflows never execute untrusted PR code.
