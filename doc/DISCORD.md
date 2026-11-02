# Discord command replies

## Run a command in a thread

Send this directly in a **server text channel**:

```text
!thread echo hello
!thread echo hello | echo result: %s
!thread ai --provider zen Hello
!thread grok --no-thread Explain this
```

The thread is attached to your requesting message. The whole expression runs
there, including pipes and embedded commands. Normal answers and working/tool
status cards go to the thread. Grok's own thread behavior reuses the outer
thread, including when `--no-thread` was supplied.

The bot needs permission to create public threads and send messages in them.
DMs, nested threads and indirect wrappers (aliases, pipes into `thread`, or
embedded `thread` commands) are unsupported. If Discord cannot confirm creation,
Yetibot does not run the nested command, fall back to the parent channel, or
retry the create request. Check permissions and send a fresh message.

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
