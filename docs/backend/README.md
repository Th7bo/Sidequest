# The backend

Sidequest's backend is a self-hosted Ktor server in this same build. Nothing in the mod depends on it —
the dependency runs the other way, through `:protocol` — so none of Ktor, Netty or Logback ends up
anywhere near a Minecraft classpath.

```
:platform-api ──> :protocol ──> :backend        (the server)
                      │
                      └──────> :platform-core   (the client)
```

## Why the server lives in this repository

A protocol change has to break **both sides at compile time**. Two repositories sharing a hand-copied DTO
agree right up until somebody edits one of them, and then they disagree at runtime, in production, on
somebody else's machine.

`:protocol` depends on `:platform-api` so that an island, an item snapshot and a permission mean the same
thing on both sides. The alternative is duplicating sixty island entries and fifteen item fields
server-side, which is a guaranteed drift.

Compiling together is necessary and not sufficient — two halves can compile and still disagree about a
serialiser configuration, a discriminator name, or a header's spelling. `EndToEndTest` in `:backend` runs
the real `DefaultBackendClient` against the real `SidequestBackend`, with nothing between them but a
fourteen-line adapter onto Ktor's in-process client.

## Running it

```bash
export SIDEQUEST_OPERATOR_TOKEN="$(head -c 32 /dev/urandom | base64)"
export SIDEQUEST_STATE=/var/lib/sidequest/state.json
./gradlew :backend:run
```

| Variable | Default | |
| --- | --- | --- |
| `SIDEQUEST_PORT` | `8710` | |
| `SIDEQUEST_HOST` | `0.0.0.0` | |
| `SIDEQUEST_STATE` | `sidequest-state.json` | the whole of the server's state |
| `SIDEQUEST_OPERATOR_TOKEN` | *unset* | **no default** — see below |
| `SIDEQUEST_OWNER_ACCOUNT` | `owner` | the account the operator token acts as |
| `SIDEQUEST_RATE_LIMIT` | `240` | requests per minute per device |

Configuration comes from the environment rather than a file, because the one value that matters is a
secret and a secret in a file ends up in a backup, a screenshot or a git repository.

**The operator token has no default.** Inventing one would mean every deployment that did not set it
shipped with the same credential. Unset is a supported state: pairing is then disabled, which is correct
for a server whose group is already set up — a credential that is not present cannot be stolen.

## Pairing

The plan's flow, and the trust comes from the approval rather than from the mod:

1. the mod asks for a code — unauthenticated, because it has no credentials yet
2. it shows the code and polls
3. an **already-authenticated operator** approves it, choosing which account it binds to
4. the mod's next poll returns tokens

**The asserted Minecraft UUID grants nothing.** A client says who it is and the server cannot check it:
verifying a Minecraft identity means Mojang's join-server handshake, and the plan forbids going near
session tokens. So the claim is recorded for display and for matching players in game, and the *approval*
is what binds the device to an account. Anybody can start a pairing; starting one gets you nothing.

**The code and the secret are separate values.** The code is six characters so somebody can read it aloud
— which also makes it guessable, so it is worthless alone. The secret is 256 bits, never displayed, and
proves a poll comes from the device that started the pairing. Polling with a wrong secret answers
`UNKNOWN` rather than "wrong secret", because the latter confirms the device id exists.

The code alphabet excludes `0`/`O` and `1`/`I`/`L`. A code that has to be repeated three times is a code
that makes people give up on pairing.

## What the server never trusts a client with

Three things on every event, and each is a specific attack if the client controls it:

| Field | Set by | If the client set it |
| --- | --- | --- |
| `senderAccount` | the server | impersonate anybody in the group |
| `sequence` | the server | rewrite the order of history |
| the permission check | the server | act beyond its permissions |

A game client is **never** granted `ADMINISTER`. It has no reason to need it, and a client that could
approve pairings is a client whose compromise hands over the group.

## Privacy is enforced at fan-out

A client that shares its island but not its position *could* be trusted to leave the position out — and
trusting a client is how a privacy setting becomes advisory. So the server redacts, field by field:

```
sender shares island with everybody, position with one person, activity with nobody
  → that person    sees island, sees position, activity UNKNOWN
  → everybody else sees island, no position,   activity UNKNOWN
```

Field by field rather than suppressing the message, because otherwise the most private choice would also
be the one that turns the feature off.

The history endpoint filters by the same rules. A replay that returned everything would make every
privacy setting a matter of when you asked.

Capability-scoped events go only to people who could have performed them: a debt names who owes what, and
somebody with no business in the ledger has no business holding a copy of it.

## Tokens

- **Access tokens** live 15 minutes, are held in memory only, and travel on every request. Persisting one
  would leave a credential on disk that a restart cannot invalidate, for no benefit — a client whose token
  stopped working refreshes.
- **Refresh tokens** are stored **hashed**. A stolen state file yields nothing replayable.
- Everything is compared with `MessageDigest.isEqual`, in constant time. `==` on a string returns as soon
  as two characters differ, which over enough attempts reveals a secret one character at a time.
- **Revocation is checked on every request**, not only at refresh. A device revoked while holding a live
  token would otherwise keep working for the rest of the token's life — exactly the window somebody
  revoking it is trying to close. Live tokens for a revoked device are dropped immediately.
- A device may revoke itself; revoking somebody else's needs `ADMINISTER`, or one compromised client could
  sign out the group.

The WebSocket takes its token in a **query parameter**, not a header. Not a preference: browser clients
cannot set headers on a WebSocket handshake, and the dashboard is a browser client. A token in a query
string is logged by proxies, which is why access tokens are short-lived and why one cannot refresh itself.

## Storage

One JSON file behind a read-write lock. **No database, deliberately**: this is a few hundred kilobytes for
a handful of people, and at that size a file is correct with no schema to migrate, no connection pool to
tune and no second process to install. Writes are atomic and backed up; a corrupt file is quarantined
rather than deleted, because it holds the group's history and is the only copy of it.

A read-write lock rather than a mutex because reads dominate — every request reads an account and a device
— and a mutex would serialise a hundred concurrent reads behind one write.

The seam is narrow enough that SQLite later touches `Store.kt` and nothing else. That property is worth
having; the database is not, yet.

The **event log is bounded** at 2000 messages. A friend group's realtime traffic is mostly presence, and
keeping every "I am in the Hub" forever grows a file nobody reads. The bound is what makes `resumeGap` a
real condition rather than a theoretical one — and handling it is why a client can survive being away for
a week.

## The client half

`DefaultBackendClient` is all the HTTP the mod does, and centralising it is not tidiness — every one of
these is easy to get slightly wrong and impossible to notice:

- retries with **exponential backoff and jitter**, only for errors that declare themselves retryable. The
  jitter is not decoration: without it every client that dropped at the same moment retries at the same
  moment, and a server that fell over gets to fall over again the instant it recovers.
- a **single** refresh-and-retry on a 401, serialised behind a mutex so ten concurrent 401s do not all
  refresh
- rate limits **honoured** as the server asked, rather than pushed through
- `DEVICE_REVOKED` and `PROTOCOL_MISMATCH` treated as terminal, so the client stops instead of looping
- **offline mode with queued writes**, which is the normal case: a rare drop at 2am on a flaky connection
  is exactly the thing worth recording
- a server-time offset measured from every round trip, because every timestamp that crosses the wire is
  eventually compared against something and two machines disagree about the time constantly

Rejected queued events are **acknowledged, not retried**. A server that refused an event will refuse it
again — a permission the group revoked while it was queued does not come back — and retrying would block
everything behind it.

`HttpTransport` and `RealtimeTransport` are interfaces over strings and bytes. Everything above them is in
`platform-core` where a fake can make the network misbehave on demand; below them is the JDK's own HTTP
client, so the mod ships **no new dependency** into somebody's game.

## Realtime

Everything difficult about a WebSocket is the reconnecting, and everything difficult about reconnecting is
what happened while you were away:

- the last sequence is remembered and sent on reconnect, so the hole gets filled
- message ids are remembered — bounded, 512 — so a resume does not show the same drop twice
- backoff is exponential and jittered, so a group of clients does not all return the instant a server
  restarts
- a payload that declares an expiry is dropped when it is past it, because a four-minute-old ping points at
  somewhere nobody is
- the resume marker only ever moves **forward**; a replayed lower sequence that moved it back would replay
  everything after it on the next reconnect

Delivery is **at-least-once**. Acknowledgement is a separate step, so a crash between sending and
acknowledging replays the entry — the receiver discards a repeat by id, and there is no way to be
exactly-once across a network boundary without a transaction.

## What is not here yet

- **The web dashboard.** Until it exists, the operator token is the only way to approve a pairing or
  administer the group. `PAIR_APPROVE` is already shaped for a dashboard rather than for the token.
- **Asset upload.** Evidence events carry metadata only; the bytes go through the asset manager, which is
  a later plan item. A megabyte of PNG on the realtime connection would block every ping behind it.
- **Acknowledgements are accepted and recorded nowhere.** Delivery is at-least-once, so nothing depends on
  them yet; the frame exists so a client can start sending them before the server starts caring.
