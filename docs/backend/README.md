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
export SIDEQUEST_HYPIXEL_API_KEY="your Hypixel developer application key"
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
| `SIDEQUEST_HYPIXEL_API_KEY` | *unset* | enables authenticated native SkyBlock profile lookups |

Configuration comes from the environment rather than a file, because the one value that matters is a
secret and a secret in a file ends up in a backup, a screenshot or a git repository.

**The operator token has no default.** Inventing one would mean every deployment that did not set it
shipped with the same credential. Unset is a supported state: pairing is then disabled, which is correct
for a server whose group is already set up — a credential that is not present cannot be stolen.

**The Hypixel key stays server-side.** Get an application key from Hypixel's developer dashboard and set
`SIDEQUEST_HYPIXEL_API_KEY` to enable `/sqprofile`. If it is absent, the rest of the backend continues to
work and the profile screen reports that its data service is unavailable.

## Deploying it

There is a `Dockerfile` and a `docker-compose.yml` at the repository root. The image runs as a non-root
user, holds a JRE and the distribution only, and is about 300 MB.

### Dokploy, Coolify, or anything that builds a Dockerfile

Point the application at this repository with build type **Dockerfile** and the path `./Dockerfile`. Then
three things, and the first two are not optional:

1. **A persistent volume mounted at `/data`.** Everything the group has ever done is one file in there.
   Without a volume a redeploy is a factory reset, and the first anybody knows about it is when their
   debts are gone.

   Use a **Volume Mount**, not a Bind Mount. The container runs as uid 10001, and Docker copies the
   image's ownership of `/data` into a fresh named volume — so the server can write. A bind mount arrives
   owned by the host user instead, and the server fails with
   `AccessDeniedException: /data/state.json.tmp` on its first write. Verified both ways.
2. **`SIDEQUEST_OPERATOR_TOKEN`**, set to a long random string:
   `head -c 32 /dev/urandom | base64`. Without it pairing is disabled and no device can be approved.
3. **A domain with TLS.** Dokploy's Traefik terminates it and proxies the WebSocket without extra
   configuration. The container port is `8710`.

TLS is not a nicety here. Every request carries a bearer token, and the WebSocket carries one in its
query string because browsers cannot set headers on a handshake. The mod's own URL rewrite is
`https://…` → `wss://…`, so a server configured over `http` would put its tokens on the wire in clear.

### Plain Compose

```bash
export SIDEQUEST_OPERATOR_TOKEN="$(head -c 32 /dev/urandom | base64)"
docker compose up -d --build
```

The compose file binds to `127.0.0.1:8710` on purpose, for a reverse proxy to sit in front of.

### Why the image build needs `--configure-on-demand`

This repository is a Minecraft mod, and Gradle configures every project by default. Configuring the
Stonecutter version nodes makes Fabric Loom provision Minecraft — several hundred megabytes and a remap —
to build a server that has nothing to do with the game. On demand, Gradle configures only `:backend` and
what it depends on, and Loom is never applied.

Gradle still insists that every `include`d project's *directory* exists, even for one it will never
configure, so the Dockerfile creates the UI modules as empty directories rather than copying them. That
also keeps a change to a UI component from invalidating the image's build cache.

### The health check

`HEALTHCHECK` runs the application with `--health-check`, which asks a running instance for `/api/info`
over loopback and exits 0 or 1. In the application rather than as a `curl` because a JRE image has no
`curl`, and installing one to ask a question the JVM can already answer is another package to keep
patched. `/api/info` is the right thing to ask: unauthenticated, touches no state, and answering it
proves the routing is installed rather than only that a port is open.

### Verified

The image was built and run before this was written: health check reports healthy, pairing is refused
without the operator token and succeeds with it, an authenticated read works and an unauthenticated one
returns 401, the state file and its backup appear in the volume, the refresh token is **not** in the
state file, and a paired device still refreshes after `docker restart`.

## Upgrading

The state file is read leniently — unknown fields are ignored — so a newer server reads an older file.
There is no migration chain on the server yet, because there has been nothing to migrate; when there is,
it belongs next to `ServerStore.load`.

`Protocol.VERSION` and `Protocol.MINIMUM_VERSION` are what let a group update at different speeds. While
they differ, a client one version behind is accepted. When they are equal, every client has to match, and
one that does not is told `PROTOCOL_MISMATCH` and stops rather than retrying.

## Configuring the mod

The mod defaults to the group's own server — `https://sq.api.th7bo.dev` — rather than shipping blank. This
is a private mod for one friend group, and asking every member to type a URL is asking for one of them to
type it wrong.

**Settings → Network → Server address** overrides it. Blank means no backend at all, which is a supported
state: the local features are most of the mod, and clearing the address should produce no errors and no
retries rather than a client that looks broken.

The field **refuses a plain `http://` address**. Every request carries a bearer token and the WebSocket
carries one in its query string, so an unencrypted server is not a typo worth tolerating.

**Settings → Network → Pair** runs the flow and reports it *in chat*, not on the screen. The point of the
flow is that the user goes somewhere else to approve the code, and a code they have to close the screen to
read is a code they cannot use. Chat persists, so it is still there when they come back.

## Pairing

### Approving one today, without a dashboard

Until the dashboard exists, the approval step is a request with the operator token. That is the *only*
missing piece — everything else already works from in game.

1. Join a world, open **Settings → Network**, press **Pair**. A code appears in chat.
2. On the server, or anywhere with the operator token:

```bash
curl -X POST https://sq.api.th7bo.dev/api/pair/approve \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $SIDEQUEST_OPERATOR_TOKEN" \
  -d '{"code":"8YZEV3","accountId":"chrooted"}'
```

3. The mod's next poll picks up the tokens and chat says `Paired. Sidequest is connected.`

The code lasts five minutes and is single-use. `accountId` has to be spelled exactly the same way every
time — it is compared for equality, so `Chrooted` would create a second, separate `MEMBER` account.

Whichever `accountId` matches `SIDEQUEST_OWNER_ACCOUNT` is created as `OWNER`; everybody else becomes a
`MEMBER`.

### The flow

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
- **Asset upload.** Evidence events carry metadata only; the bytes go through the asset manager. A megabyte of
  PNG on the realtime connection would block every ping behind it.

  The client half now exists and expects `GET /v1/assets/<sha256>` to return the bytes. What is missing is the
  other direction: something that accepts an upload, hashes it, applies the same limits the client applies, and
  hands back the id. Until then an asset has to be put in place by hand. Note that the server must re-run the
  checks rather than trusting the client's — the client validates what it *receives*, which protects that
  client and nobody else.
- **Acknowledgements are accepted and recorded nowhere.** Delivery is at-least-once, so nothing depends on
  them yet; the frame exists so a client can start sending them before the server starts caring.
