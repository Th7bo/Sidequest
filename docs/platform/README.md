# The mod platform

Sidequest is two frameworks that do not know about each other.

The **UI framework** (`ui-api`, `ui-core`, `ui-components`) draws configuration screens
and HUDs. It is documented in [../guide](../guide/getting-started.md).

The **platform** (`platform-api`, `platform-core`) is everything else a feature needs:
somewhere to declare itself, typed events, a scheduler, and a boundary between it and
Minecraft. That is what this document covers.

Neither module depends on the other. A feature usually uses both, and either could be
replaced without touching the other.

---

## Why the modules are split this way

| Module | Contains | Knows about Minecraft |
| --- | --- | --- |
| `platform-api` | interfaces, declarations, events, models | no |
| `platform-core` | the bus, scheduler, registries | no |
| `platform-testkit` | fakes for all of it | no |
| `src/main/.../platform/minecraft` | the adapters | yes, exclusively |

Minecraft is not on the classpath of the first three. That is the enforcement mechanism,
not a convention: feature code physically cannot reach a Minecraft class, so
version-specific detail has nowhere to leak to. It also means the whole platform is
testable at full speed with no game running — 151 tests that take a couple of seconds.

---

## Writing a feature

```kotlin
val rareDropAnimation = feature("drops.rare_animation") {
    displayName = "Rare drop animation"
    category = FeatureCategory.VISUALS
    description = "Plays a cinematic when something rare drops"
    supportedVersions = VersionRange.atLeast("26.1")
    config = RareDropConfig

    listen<ClientTickEvent> { onTick(it) }
    command("testraredrop") { play(sample()) }
}
```

Or as a class, when the feature has state:

```kotlin
class SessionDiagnostics(private val client: GameClient) : Feature {

    override val descriptor = FeatureDescriptor(
        id = SqId.sidequest("dev.session_diagnostics"),
        displayName = "Session diagnostics",
        category = FeatureCategory.DEVELOPER,
        description = "Counts ticks and sessions",
    )

    override fun onEnable(context: FeatureContext) {
        context.listen<ClientTickEvent> { ticks = it.tick }
        context.every(1.seconds) { uptimeSeconds++ }
        context.command("sqdiag") { report() }
    }
}
```

Both forms get the same guarantee: **everything registered through the context is undone
when the feature is disabled.** Listeners, jobs, repeating tasks, commands. There is no
unregister call to forget, because there is no handle to keep.

### What a feature declares

Everything in `FeatureDescriptor` is answerable without loading the feature, which is what
lets a settings screen list a disabled feature and a permission audit list what the mod
*could* ask the backend for:

- id, display name, category, description
- supported Minecraft versions — outside the range it is refused, not crashed
- dependencies — enabled first, and cycles are rejected at registration
- config section reference
- backend permissions and realtime subscriptions
- experimental flag

### What a feature is given

`FeatureContext`, and nothing else. It cannot reach Minecraft, the filesystem, the network
or another feature through it. Those arrive as services, each with its own boundary.

---

## Events

```kotlin
context.listen<MinecraftJoinEvent> { event ->
    log.info { "Joined ${event.serverAddress}" }
}
```

A listener registered for a type also receives its subtypes, so a listener on
`SidequestEvent` sees everything and one on a family base class sees that family.

### Priorities

`FIRST → EARLY → NORMAL → LATE → MONITOR`. Same priority means registration order, so
dispatch is deterministic and therefore testable. Reach for a non-default priority only
when the ordering is load-bearing, and say why at the registration site.

`MONITOR` runs after everything else **including cancelled dispatches**, and must not
change anything — by then the others have already acted on the state it would change.

### Dispatch modes

| Mode | Runs on | Can cancel |
| --- | --- | --- |
| `IMMEDIATE` | the posting thread, inline | yes |
| `MAIN` (default) | the client thread | no |
| `ASYNC` | a background thread | no |

The default is the strictest one on purpose. A listener touching Minecraft state from the
wrong thread produces a crash that reproduces once a week and never in a test.

Only `IMMEDIATE` can cancel, because cancellation has to be decided before `post` returns.

### Cancellation

Opt-in, via the `Cancellable` interface. Most events are statements of fact — a player
*did* die — and making those cancellable would invite listeners to pretend otherwise.

### Failure isolation

A listener that throws is logged and recorded in the trace; the remaining listeners still
run. One feature cannot break another, and a post made by an adapter has no useful way to
handle someone else's failure.

### Tracing

`bus.isTracing = true` records the last 256 dispatches: what was posted, how many
listeners ran, how long it took, whether it was cancelled, and who threw. Events with
*zero* listeners are recorded too — "nothing happened when I did X" is a different answer
from "the event never fired".

### Which events exist

The client lifecycle: `MinecraftJoinEvent`, `MinecraftDisconnectEvent`, `ClientTickEvent`,
`ClientShutdownEvent`, `FeatureStateChangedEvent`.

The game context: `SkyBlockJoinEvent`, `SkyBlockLeaveEvent`, `IslandChangedEvent`,
`SubLocationChangedEvent`, `ServerChangedEvent`, `ProfileChangedEvent`.

Chat, from the parser: `ChatMessageEvent` plus the derived family under
`ChatDerivedEvent` — see [Chat](#chat).

The rest of the catalogue in `mod.plan` — dungeon, achievement, social — is defined
alongside the service that emits it. An event whose payload nobody fills is a guess at a
data model, and guesses are what migrations are made of.

---

## Chat

One place classifies chat. A feature declares the shape of a line and the event it means,
and never touches a chat string again:

```kotlin
context.chatRule(
    chatRule(
        id = SqId.sidequest("myfeature.debt_paid"),
        regex = """§aDebt settled by (?<who>.*)§a\.""",
        fixtures = listOf("§aDebt settled by §b[MVP§d+§b] Notch§a."),
    ) { match ->
        match.playerName("who")?.let { DebtSettledEvent(it, match.message) }
    },
)
```

The built-in rules cover the channels (party, guild, co-op, private, public), party
membership, rare drops, skill level-ups, dungeon and Kuudra completion, and auctions. They
live in `HypixelChatRules` and are registered by the platform, not by a feature — a feature
being switched off must not stop the mod knowing it joined a party.

### Three views of a line, and why

`ChatMessage` exposes the same text three ways, and a pattern says which it wants. Getting
this wrong fails silently and looks like Hypixel changed a message.

| Target | What it is | Use it when |
| --- | --- | --- |
| `FORMATTED` (default) | codes intact, `§r` removed | colour carries meaning: `§6§lRARE DROP!`, `§9Party §8>` |
| `PLAIN` | codes removed, layout intact | leading whitespace matters: the dungeon banner, the level-up indent |
| `CLEAN` | trimmed, spaces collapsed, invisibles gone | the words are all that matter |

**Why `§r` is removed.** How many resets a component serialises to depends on how the
message was assembled, so the same visible line can arrive as `§r§b[MVP§r§c+§r§b] Notch` or
as `§b[MVP§c+§b] Notch`. A pattern written against one silently fails on the other. Resets
carry no information a pattern can use — `§r` means "back to plain", and nothing keys off a
run *lacking* a colour — so dropping them costs nothing and removes a whole class of
almost-working pattern.

### Patterns carry their own fixtures

Every pattern ships the real lines it was written against, and `verifyFixtures()` runs them:

```kotlin
val failures = platform.chat.verifyFixtures()
```

Checked in the headless suite, again at startup, and again in the in-game test. Not
redundant — patterns can be replaced at runtime, and a replacement has not been through the
test suite. A pattern with no fixture is a pattern nobody has seen work; `ChatRulesTest`
asserts the exact set of those, so adding one is deliberate rather than accidental.

`ChatRulesTest` also asserts that **each fixture is classified by exactly one rule**. The
parser gives every rule a look rather than stopping at the first match — first-match-wins
would mean the second feature to register on a line silently stops working — and that is
only safe if the patterns do not overlap.

### Versioning

A rule whose pattern id is already registered replaces it if its `version` is higher, and is
refused if it is not. That is how a corrected pattern can arrive from anywhere — including
the backend, later — without the caller knowing what it is competing with.

### Duplicate suppression

Hypixel sends the same line twice. `ChatMessageEvent` is posted for every line including
repeats, with `isDuplicate` set; rule-derived events are **not** posted for a repeat. A
feature mirroring chat wants what the player saw; a feature counting loot must not count the
same drop twice.

The window is 150 ms, deliberately short. Hypixel's duplicates arrive within a tick or two,
and a window long enough to catch a slow one is long enough to swallow two genuine drops in
a row. Losing a real event is the worse failure.

### Click actions are the durable half

The most reliable thing about a Hypixel prompt is not its wording:

```kotlin
match.message.commandStartingWith("/party accept")
```

`/party accept Notch` behind the invite prompt has outlived several rewordings of the
prompt. That is why `SqText` keeps the click event rather than the parser matching a
flattened string.

### Where the patterns come from

Every pattern and every fixture was taken from SkyHanni's `RepoPattern` groups, and the
fixtures are their `REGEX-TEST` lines verbatim. Hypixel's chat is documented nowhere, years
of corrections live in those formats, and rediscovering the exact shape of the guild rank
suffix by observation would take the same years. The implementation, the event model and the
matching strategy are ours.

Two things follow from that, and both are rules:

**Never write a Hypixel pattern from memory.** Check `../SkyHanni` first. An earlier version
of the scoreboard cleaner deleted Hypixel's texture-pack glyphs as "padding" and would have
broken every pattern that matched one, silently.

**A fixture is an observation, not a derivation.** Synthesising one from the pattern it is
meant to test proves nothing and reads as evidence. Where no recorded line exists, the
pattern is marked unverified and named in the test.

### Debugging

`/sqchat` toggles tracing and prints the counters. When a rule stops firing the question is
whether Hypixel changed the message or the line never arrived at all, and only the log
distinguishes them — they look identical from the outside and have nothing in common.

---

## Scheduling

```kotlin
context.onMain { }                       // client thread, inline if already there
context.async { }                        // background, must not touch the game
context.after(5.seconds) { }
context.every(1.seconds) { }
context.debounce(200.milliseconds) { }   // coalesce a burst into one run
context.throttle(1.seconds) { }          // at most once per interval
```

Everything is owned, so unloading a feature cancels its in-flight work. A repeating task
outliving its feature is the single most common way a mod like this leaks.

A repeat that overruns its period does not stack — the next run is skipped rather than
queued, because a slow repeating task that queues turns a hitch into a spiral.

For retries, `withRetry(RetryPolicy())` gives exponential backoff with jitter. The jitter
is not decoration: without it, every client that dropped at the same moment retries at the
same moment, and a backend that fell over gets to fall over again.

---

## The Minecraft boundary

`GameClient` and `GameLifecycle` are the entire surface. Two adapter classes implement
them, and nothing above them imports a Minecraft class.

Deliberately small. The temptation is to mirror `Minecraft` wholesale, which recreates the
coupling the interface exists to prevent, just with an extra hop. Each method is added
when something needs it.

What exists now: version, thread checks, client-thread dispatch, local player identity,
connection state, screen state, client messages, server commands, tick count. Player,
world, item, entity and raycast wrappers arrive with the pass that needs them.

### Three things worth knowing

**`isScreenOpen` is tracked, not read.** Minecraft 26.x stopped exposing its current
screen, so the adapter follows the open/close events. On the title screen a screen *is*
open — which is the correct answer, and the one the first version of the in-game test
mistook for a bug.

**Fabric's callbacks cannot be removed**, so the lifecycle adapter registers one of each
for the session and fans out to its own lists. Without that inversion, "unregister" would
be a lie and every feature reload would leave another dead callback behind.

**Brigadier nodes cannot be removed either.** A command node therefore holds only a
*name* and looks the command up in the registry each time it runs, so a disabled feature
stops answering immediately even though its node is still in the tree. Capturing the
handler in the node would leave a disabled feature responding to commands, which is the
one guarantee the ownership design exists to provide.

### The command bridge, and a lesson about tests

`/sqdiag` shipped as "Unknown command" with the in-game test passing. The test asserted
that the *registry* contained the command; nothing asserted that the *game* would run it,
and nothing did — the registry was bookkeeping and no adapter had been written.

The test now parses the name against `client.connection.commands`, which is the client's
real command tree. It was verified to fail without the bridge before being kept.

The general shape of the mistake: **an assertion about the mod's own bookkeeping is not an
assertion about behaviour.** When something crosses a boundary, test the far side.

---

## Testing

`platform-testkit` fakes everything:

```kotlin
val scheduler = TestScheduler()        // nothing runs until you advance it
val client = FakeGameClient()          // and asserts what the mod sent to the server
val lifecycle = FakeGameLifecycle()    // fire join/tick/disconnect by hand
val sink = RecordingLogSink()          // the platform swallows failures; this is the evidence
```

`TestScheduler.advance(2.seconds)` moves a virtual clock. A real scheduler in a test buys
flakiness and nothing else.

One trap worth knowing if you test `DefaultScheduler` directly with `runTest`: `runTest`
drives the dispatcher to idle when the body returns, and a repeating job never becomes
idle. Leaving one running hangs the whole test run instead of failing one test. Cancel the
owner in a `finally`.

The in-game tests cover the half no fake can. `PlatformRuntimeTest`: that a tick event
actually fires on a real client, that a command actually registers, and that disabling a
feature really does stop it. `BoardReaderTest`: that the adapter pulls the right strings out
of a real scoreboard, team prefixes and all. `ChatBridgeTest`: that a component sent over the
network comes back out as a typed event.

`ChatBridgeTest` is the clearest example of why the far side has to be tested. Every chat
fixture in the suite would still pass if `toLegacyFormatting` emitted the wrong colour code,
or dropped the codes, or put them after the text — and every rule in the mod would silently
stop matching. So the test asserts the exact rendered string, not just that something
arrived.

---

## Rules

These are the ones that keep the architecture from eroding. They are in `mod.plan`; they
are repeated here because this is where they get broken.

Feature code must not:

- import a Minecraft class
- parse raw scoreboard or tab-list text
- match a regex against a chat line — declare a `ChatRule` instead
- perform HTTP requests or open WebSockets
- read or write JSON files
- download remote assets
- resolve a player by username alone

Each of those has, or will have, a service. The first is enforced by the compiler; the
rest are enforced by review, and by the fact that doing it the wrong way is more work.
