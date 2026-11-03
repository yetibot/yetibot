# Discord command replies

## Inspect errors and structured results

**Show details** expands a completed AI reply's frozen tool-call summary inside
the same card and becomes **Hide details**. Hide restores the original answer,
files/media and elapsed caption. Long answers may be shortened temporarily
while the summary is open. This shows only retained tool names, outcomes and
budget counts, not raw arguments/results or private reasoning. It does not
rerun tools. Data, tool and error panes replace one another rather than accumulating.

**Error details** expands retained public diagnostics inside the original
reply, without sending another message or rerunning the command. **Hide error
details** restores the original card. Its field/Back/paging controls stay inline.
Files and one original elapsed caption are preserved; a long error may be
shortened only while details are open. Weather failures can include the service, failure stage/kind and
numeric HTTP status. If additional details were not retained, the viewer says
so. Raw responses, credentials, headers and private AI internals are not shown.
Unexpected Discord command exceptions use a safe summary rather than printing
an arbitrary exception message.

**Explore data** expands inside the original reply and becomes **Hide data**.
It is available for explicitly reviewed public projections: `weather`,
`weather forecast`, `version` and `changelog`. The tree initially shows two
levels, or all available levels for shallower data. **Expand** shows one more
level everywhere; **Collapse** shows one fewer. Both reset to the first page.
Collapsed objects/arrays show type/count summaries instead of requiring a
one-field dropdown. Large JSON previews use **Previous** and **Next** at the
same depth; pages may continue a document. Controls disable at their bounds.
Hide data restores the exact original answer, files/media and elapsed caption.
Reopening starts at the two-level overview. Weather snapshot temperatures are
labeled Celsius and wind speeds km/h, regardless of the ordinary answer's
units. Pipeline data stays unchanged. Raw result maps do not automatically get
a public data button.

Snapshots freeze at command completion and are read-only. Public navigation can
be used by anyone in the same reply channel/thread; rerunning remains
requester-only with confirmation. Edits, cache eviction and process restarts
expire old viewers. Inspection never fetches an upstream response or repeats
inference/side effects. All panes update only the tracked original reply, with
snapshot-token, revision and final ownership checks. Long answers can be
shortened temporarily while a pane is open; Hide restores them in full.

Data previews page at1,500 UTF-16 characters, preferably at line boundaries;
error navigation retains eight fields/items per page. At most eight data
results and eight error records are retained. Snapshots limit nesting,
fields/items per container and string sizes;
large data may be shortened. Missing or unreviewed fields are not a claim that
the upstream response was empty. Adding support requires a reviewed field
projection, not blanket map exposure.

## Run a command in a thread

Send this directly in a **server text channel**:

```text
!echo hi | thread
!echo hi | thread | echo result: %s
!weather | thread
!thread echo hello
!thread echo hello | echo result: %s
!thread ai --provider zen Hello
!grok Explain this
!grok Explain this | thread
!thread grok Explain this
```

The thread is attached to your requesting message. Native `| thread` passes the
piped value/data through unchanged; it does not parse that value as a command.
It switches ordinary delivery for later stages and the final answer. Earlier
stages have already run, and any output they already sent cannot be moved.
Parent working cards are retired when delivery moves; new progress can start in
the thread. Explicit cross-channel sends keep their existing semantics.

The prefix form `!thread <command>` confirms the thread **before** executing the
whole expression, including pipes and embedded commands. Use that form when all
work, including initial status/live output, should start in the thread. Grok
replies in the current channel/thread by default and creates no thread itself.
An intermediate Grok answer is a literal undecorated value for later stages;
`grok … | thread` moves its final output without an earlier independent answer.
The legacy `--no-thread` flag remains accepted and honors explicit thread delivery.

The bot needs permission to create public threads and send messages in them.
DMs and nested threads are unsupported. If creation is uncertain, Yetibot may
make one bounded read-only lookup to confirm the attached thread; it never
retries the creation POST. Without confirmation the thread step fails, no
following pipe stages run, and the prefix form does not execute its nested
expression. There is no silent parent-channel fallback. Check permissions and
send a fresh message. Direct-only admin controls still reject pipes, aliases,
wrappers and computed expressions.

Edits and requester-confirmed **Run again** reuse the same confirmed thread.
The requesting user, original message and parent-channel settings remain the
source of authorization and context. Moving delivery does not grant permissions.
Controls that require direct standalone requests, such as `!ai default reset`,
still reject a `thread` wrapper. Explicit cross-channel commands retain their
existing semantics; `thread` redirects the ordinary reply destination.

## Shared elapsed caption

Rich command answers show a small elapsed footer such as `2.4s`, including
ordinary commands, errors, media and exact Markdown attachments. It measures the
request's command evaluation using a monotonic clock, including preparation of
the thread; final transport/cleanup after evaluation is not included. Live
answers and working cards show elapsed at publication; terminal reconciliation
updates live answer timing to the completed evaluator duration.

AI metadata retains model, known tool count and honest cost labels, but lets the
shared renderer own elapsed timing rather than displaying it twice. The caption
is transport-only: intermediate pipe values and Slack formatting are unchanged.

The combined rich-text budget remains 4,000 UTF-16 characters. Space is reserved
for timing; answers that no longer fit have a readable excerpt and an exact
`answer.md` attachment instead of dropping content.
