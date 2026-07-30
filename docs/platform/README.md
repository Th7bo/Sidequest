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
| `protocol` | the wire format, shared with the backend | no |
| `src/main/.../platform/minecraft` | the adapters | yes, exclusively |

The backend is in this build too, and is documented in [../backend](../backend/README.md). Nothing in the
mod depends on it — both depend on `:protocol`, so a wire-format change breaks both sides at compile time
rather than at runtime on somebody else's machine.

Minecraft is not on the classpath of the first three. That is the enforcement mechanism,
not a convention: feature code physically cannot reach a Minecraft class, so
version-specific detail has nowhere to leak to. It also means the whole platform is
testable at full speed with no game running — 1,149 tests that take a couple of seconds.

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
`SubLocationChangedEvent`, `ServerChangedEvent`, `ProfileChangedEvent`, `ActivityChangedEvent`.

Players and the party: `PlayerFirstSeenEvent`, `PlayerRenamedEvent`, `PlayerPresenceChangedEvent`,
`PartyChangedEvent`, `ReadyCheckChangedEvent`.

Chat, from the parser: `ChatMessageEvent` plus the derived family under
`ChatDerivedEvent` — see [Chat](#chat).

The boards: `ScoreboardChangedEvent`, `TabListChangedEvent` — see [The boards](#the-boards).

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

## The boards

The scoreboard and the tab list are polled every tick, parsed into readings, and merged into
the game context. A feature reads the context, not the boards.

```kotlin
val context = context.gameContext.context
if (context.isInDungeon && context.isReliable) { … }
```

### The scoreboard

`ScoreboardReading` names what the board says: area, server, guest state, dungeon floor,
Kuudra tier, game mode, purse, bits, date, time. Anything it does not name is in `values`,
keyed by the `Key:` on the line:

```kotlin
reading.value("Sowdust")      // "6.5k (+912)" — exactly what the board said
reading.number("Purse")       // separators removed, or null if it was not a plain number
```

`values` is the escape hatch that stops a feature growing its own scoreboard pattern.

**The floor and the tier come from the area line, not from a second pattern.** `The Catacombs
(F7)` and `Kuudra's Hollow (T5)` are one shape, and reading the suffix off the area we already
parsed cannot disagree with the area — which a separate dungeon pattern could, and eventually
would.

**An abbreviated amount reads as null, not as a wrong number.** `6.5k` with the punctuation
stripped is 65, and a purse off by a factor of a hundred is worse than an unknown purse.

### The tab list

Not a list of lines — a board of widgets. A header line names the widget, its value lines
follow, and the next header ends it:

```kotlin
reading.has(TabWidget.COMMISSIONS)        // in the Dwarven Mines, doing commissions
reading.linesOf(TabWidget.POWDER)         // ["Mithril: 1,000", …]
reading.partyMembers                      // ["Alice", "Bob"]
reading.playerCount                       // Players (N) + Guests (N)
```

**Which widgets are present is the most useful thing on the board.** A `Commissions:` widget
means the player is doing commissions; `Visitors: (3)` means the Garden. That is a better
activity signal than any single value, and it is what §14's activity detection will be built
on.

Two layout quirks are handled in `TabListLayout.normalise`, and both bite hard if they are
not:

- **The tab list is 80 entries in four columns of twenty**, and `Players (N)` / `Info` are
  repeated at the top of each column. Read as widget headers, each repeat starts a fresh
  widget and every widget after it is attributed to the wrong one.
- **A widget split across a column boundary repeats its header.** Treating the repeat as a
  new widget throws away the first half — for the party widget, half the party.

Player names come out with rank tags, guild tags, level brackets and emblems stripped, by
shape rather than by a list of known tags (`HypixelNames`). A line with nothing name-shaped in
it yields nothing rather than a guess.

### Change events

```kotlin
context.listen<ScoreboardChangedEvent> { event -> event.added.forEach { … } }
context.listen<TabListChangedEvent> { event -> event.addedWidgets… }
```

Both carry what changed rather than only the new state. "A line appeared saying the run
started" is a different question from "the board contains that line", and the second stays
true for as long as the line is up — a listener written against it fires on every poll.

The scoreboard diff is over the **cleaned** lines. Hypixel animates the colours on several of
them, and a raw diff would report a change several times a second on a board that says exactly
the same thing.

### Debugging

`/sqboard` writes both boards and both readings to the log — raw lines included, with `§`
rendered as `&`. A line that looks right and does not match almost always has an invisible
character or a formatting code where nobody expected one, and only the raw form shows that.

### What is a fixture and what is not

The scoreboard lines in the tests are recorded Hypixel output, taken from SkyHanni's
`REGEX-TEST` comments. The tab lists are **constructed**: they exercise a splitting algorithm
that is ours rather than an observation about Hypixel, and hand-building one is the only way to
cover a column boundary at all. The widget header patterns come from SkyHanni and are
unfixtured there too — Hypixel documents none of this.

---

## Items

**Never persist an `ItemStack`.** That is the rule the item model exists to make easy to
follow. A stack is a live game object: it belongs to a version of Minecraft, it needs a
registry to mean anything, and the moment Mojang moves a component the saved copy is
unreadable. A record of a rare drop from eighteen months ago has to still be readable, and so
does a debt that names the item it is for.

`SqItem` is the snapshot — plain data, fully serialisable, no game type anywhere in it:

```kotlin
val item = stack.toSq(acquisition = ItemAcquisition.from(gameContext.context, now, DROP))
item.skyblockId        // "HYPERION" — the kind of item, stable across renames
item.itemUuid          // this particular one, when Hypixel gave it an identity
item.rarity            // MYTHIC, read off the last lore line
item.upgrades.stars    // 10
item.summary()         // "Withered Hyperion [Mythic] (withered, 10★, recombobulated)"
```

Two identifiers, and the difference matters. `skyblockId` is the *kind* — Hypixel changes
display names and has never changed one of these, so anything comparing items compares this.
`itemUuid` is *this one*, and only items Hypixel considers unique carry one, which is exactly
the set worth tracking individually: a lent Hyperion has one, a stack of cobblestone does not.

### Where the logic lives

The interpretation is in `platform-core`; only the four things Minecraft knows come from the
adapter:

```
ItemStack ──(adapter)──> minecraftId, name, lore, count + ItemDataSource
                                                              │
                                          SkyBlockItemReader ──┘   (platform-core, testable)
```

`ItemDataSource` is a read-only view of Hypixel's item tag. It exists so every attribute name
— and there are two dozen — sits where a test can drive it from a map:

```kotlin
SkyBlockItemReader.read(…, data = FakeItemData("id" to "HYPERION", "upgrade_level" to 10))
```

Without that seam the whole model's logic would be behind a Minecraft type, untestable except
in a running game. Which is also the reason `ItemReaderTest` exists as a gametest: the headless
suite would pass unchanged if the adapter read the wrong component.

### Three things that are not obvious

**Rarity has no attribute.** Hypixel writes it as the last lore line, in caps, with the
category beside it — `MYTHIC DUNGEON SWORD`. It is searched bottom-up, because downwards an
ability description mentioning "RARE" wins over the real line. `RARE CROP` is excluded by name;
it appears in Garden lore and is not a rarity.

**Stars live in two attributes.** `upgrade_level` is the modern one; `dungeon_item_level` is the
old one, still present on items nobody has touched since, and only meaningful on a dungeon item.
Reading only the first undercounts an old sword to zero stars.

**Gemstone slots arrive in three shapes**, all of them live. A typed slot names its gemstone in
the key (`JADE_0`). Some slots hold the quality in a nested tag instead of directly. A universal
slot's key does not name a type at all — that is in a sibling key with `_gem` appended, which is
why the companion keys have to be skipped as slots.

### Estimated value and the escape hatch

`estimatedValue` is null until a price source exists; null means "nobody has priced this", not
"worthless". The field is there so a record written today is readable by the feature that adds
pricing, rather than the model changing shape under every stored snapshot.

`extra` holds every flat attribute that has no field, as strings — the same escape hatch as the
scoreboard's `values`. An attribute *with* a field is excluded from it, because two sources for
one value is two sources that can disagree. Nested tags are skipped rather than flattened: a
flattened `pet_info` blob in a string map is not usable by anything, and pretending otherwise
invites a feature to parse it.

---

## Players

**Never key anything on a username.** Minecraft names can be released and claimed by another
account, so a debt recorded against "Notch" can end up owed by a stranger. A UUID cannot be taken
over.

That is enforced structurally rather than by review. Nothing in the directory is *stored* under a
name; there is a name index, and it is a resolution cache that could be rebuilt from the identities
alone:

```kotlin
players.byId(playerId)              // the only lookup that is always right
players.resolveUsername("Notch")    // best-effort, documented so, can be wrong
```

Renames are recorded rather than overwritten, so a name written down a year ago still finds the
person. Where a name has been *taken over*, the current holder wins — right for "invite this
person", and exactly why nothing durable may store one.

The directory learns almost everything from the online player list, polled once a second: being in a
lobby with somebody is the client's one reliable source of "this name belongs to this account".
Nicknames are ours, never sent anywhere, and never used for resolution.

`PlayerPresence` is separate from identity because it changes on a different timescale — an identity
is worth persisting and a presence is worth nothing five minutes later. A disconnect calls
`forgetPresence()`, not `clear()`: identities do not stop being true because we left a server.

`isLocationShared` is the permission gate. A presence carries a location only when the player has
opted in, and "not shared" is indistinguishable from "no data" on purpose — a feature that could tell
them apart could tell the player something they did not agree to reveal.

### Targeting

```kotlin
targeting.crosshairTarget           // raycast, line of sight required
targeting.lastTargeted              // so a GUI can open after looking away
targeting.resolveTarget(name)       // the whole chain: name, else crosshair, else last
targeting.nearby(maxDistance = 16.0)
```

The crosshair pick is a real raycast against each player's bounding box, not the
angle-to-look-vector approximation. The approximation prefers whoever is nearest the centre of the
screen regardless of who is in front, so with two people in a line it targets the wrong one — and a
"point at somebody" feature that picks the person behind them is worse than no feature.

`TargetedPlayer` is a reading, not an entity. A feature holding an entity holds a reference into the
world that goes stale when the player leaves render distance.

---

## The party

**Features must not parse party chat.** The party is assembled once, from the chat rules and the tab
widget, and a feature reads `context.party`. Six features each watching for "joined the party." is
six patterns to fix when Hypixel rewords it, and five will not get fixed.

```kotlin
party.party.members          // [Throwpo (leader), CalMWolfs]
party.party.confidence       // NONE | TRACKED | CONFIRMED
party.readyCheck             // null when none is running
```

**Chat is a stream of changes, not a state.** A session that began mid-party never saw the joins, so
the accumulation is silently empty there — which is what `PartyConfidence` exists to say out loud.
The tab widget is a statement of who is in the party *right now*, so it wins where it has anything
to say; where it does not, the accumulation stands at `TRACKED`.

The service subscribes at `IMMEDIATE`, so the party is already up to date by the time a
`PartyMemberJoinedEvent` reaches a feature. A feature reacting to somebody joining by reading the
member list must not see the list from before they joined.

`ReadyCheck` is a value with a deadline rather than a live object with a timer: a screen can show the
countdown without asking anybody, and there is no timer to leak when a feature unloads. A response
from somebody who was not asked is ignored — a check is over a fixed set of people, and letting a
latecomer answer would change what "everybody is ready" means.

---

## Activity

What the player is doing, at the level a feature cares about, with the confidence attached:

```kotlin
val reading = gameContext.context.activity
reading.activity      // DUNGEONS, MINING, GARDEN, …
reading.confidence    // CONFIRMED | PROBABLE | GUESSED | NONE
reading.reason        // "scoreboard names floor F7"
```

Three tiers of signal, and the ordering is the whole design:

| Signal | Confidence | Example |
| --- | --- | --- |
| the game states it | `CONFIRMED` | the scoreboard names the floor, or the Kuudra tier |
| a widget implies it | `PROBABLE` | `Commissions:` only appears while doing commissions |
| the island alone | `GUESSED` | the Crystal Hollows is for mining and nothing else |

Falling through all three gives `UNKNOWN`, which the plan asks for explicitly. An invented activity
is worse than an absent one, because features act on it — so the Hub is `EXPLORING` at `GUESSED` and
never anything more definite.

`reason` is not decoration. Detection is a pile of heuristics, and when one is wrong the only useful
question is "what made you think that". Reconstructing the answer from a log afterwards is not the
same as having it.

`GameContext.isDemanding` combines the place and the activity, which is the distinction the island
alone cannot make: standing in the Crimson Isle is not demanding and being mid-Kuudra is.

---

## Storage

**Features must not read or write JSON.** Eleven features each inventing an atomic-write is eleven
chances to get the corruption case wrong, each losing a different user's data.

```kotlin
val ledger = context.store(
    name = "ledger",
    scope = StorageScope.Profile(playerId, profile),
    serializer = Ledger.serializer(),
    default = { Ledger() },
    validate = { if (it.debts.values.any { debt -> debt < 0 }) "a debt cannot be negative" else null },
)

ledger.update { it.copy(debts = it.debts + (who to amount)) }
```

### The scope is part of asking

| Scope | One copy per | For |
| --- | --- | --- |
| `Global` | installation | keybinds, developer flags |
| `Account(playerId)` | Minecraft account | friend list, cosmetic loadout |
| `Profile(playerId, profile)` | SkyBlock profile | progression, ledgers, anything per-profile |
| `Cache` | installation, disposable | anything that can be deleted without consequence |

Not filing tidiness — correctness. An Ironman profile's ledger has nothing to do with the main
profile's, and a feature that stored them together would show one profile's numbers on the other.
Making the scope part of getting a repository means a feature decides once rather than getting it
wrong per write.

`Profile` is keyed on the account *and* the name, because a profile name is only unique within an
account — two people can both have a "Mango", and keying on the name alone would merge their data the
moment files were shared or synced.

### What happens when things go wrong

The plan's whole requirement list lives here, and each item is a specific failure:

- **Atomic writes.** Temp file in the same directory, then an atomic move. A crash mid-write leaves
  the previous file, never a truncated one.
- **Backups.** The previous version is kept as `.bak` before each write.
- **Corruption detection and quarantine.** An unreadable file is moved to `.corrupt-<timestamp>`, not
  deleted. Somebody whose ledger stopped loading wants the file; a mod that deleted it destroyed the
  only copy. The timestamp means a second corruption does not erase the evidence of the first.
- **Fallback recovery.** Live file, then backup, then defaults. A corrupt file with a good backup
  loses at most the last save.
- **Validation.** `validate` rejects a value that *parses* and is still wrong. Parsing is not
  validation: a negative coin count deserialises perfectly. The file is left in place so it can be
  inspected.
- **Migrations.** A chain, one version per step, applied in order — so a file from any past version
  reaches the present by composition rather than a special case per starting version. A **gap** in the
  chain quarantines rather than guesses: applying the rest of the chain to a document that skipped a
  step produces plausible nonsense, which is worse than a message somebody can act on.

Every load returns a `StorageReport` rather than throwing. A feature cannot handle "the file was
corrupt" usefully and the user can — but only if somebody tells them.

### The offline queue

```kotlin
val outbox = storage.queue(id, scope, DropRecord.serializer())
outbox.enqueue(record)
val batch = outbox.peek()      // does not remove
outbox.acknowledge(batch.map { it.id })
```

The interesting moments happen while offline: a rare drop at 2am on a flaky connection, an achievement
earned while the homelab reboots. Holding those in memory loses them on the next crash; dropping them
leaves holes in the group's history exactly where somebody will look.

**Delivery is at-least-once.** `acknowledge` is a separate call, so a crash between sending and
acknowledging replays the entry — the receiver discards a repeat by its id, and there is no way to be
exactly-once across a network boundary without a transaction.

**At capacity the oldest is dropped, not the newest refused.** A queue that refuses new entries when
full stops recording the present in order to preserve a past nobody has looked at.

The timestamp is when the entry *happened*, not when it is sent, which is the whole point of queueing:
a drop recorded at 2am and delivered at 9am belongs at 2am in the history, and only the client knows
that.

### On duplicating the UI store

The UI framework has its own store with the same properties. That is deliberate: the platform and the
UI framework do not depend on each other, and sharing this would mean one importing the other or a
third module both import. Two atomic-writes is the price of the split; being able to replace either
framework without touching the other is what it buys.

---

## Permissions and privacy

Two different questions, and the plan's flat list hides the difference:

```kotlin
permissions.can(actor, Permission.SEND_PINGS)                    // capability — role decides
permissions.shares(Permission.VIEW_EXACT_POSITION, viewer)       // disclosure — the subject decides
```

"Send pings" is about what *I* may do. "View exact position" is about what somebody has allowed to be
known about *them*. Treating both as one role check produces a privacy gate that is decorative: an
admin would see a member's exact position because admins can do things, which is not what agreeing to
share your island agreed to. So `PermissionKind` is on every permission, and asking the wrong method
returns **false** with a warning rather than a plausible answer — a caller that got there has a bug,
and a helpful answer would hide it while leaking the thing the split protects.

### Capabilities

`GUEST → MEMBER → ADMIN → OWNER`, ordered so a check reads as "admin or better". An unknown player is
a `GUEST`: somebody the group has not decided about gets the least, not the default.

The defaults follow one rule — anything that costs somebody else something needs `ADMIN`:

| Permission | Role | Why |
| --- | --- | --- |
| send pings, waypoints, ready checks, sounds | `MEMBER` | the point of the mod |
| create debts, upload evidence | `MEMBER` | adding to the record |
| confirm payments, edit evidence, moderate | `ADMIN` | settling or rewriting the record |
| manage shop rewards | `OWNER` | rewards are currency |

Per-user overrides beat the role **in both directions**. "Let them run ready checks" needs a grant
above the role; "that person cannot be trusted with the soundboard" needs a denial below it. A
role-only model can do neither.

### Disclosures

Off or on per permission, and the one that reveals *where a person physically is* is treated
differently:

- online status, activity, island — shared by default
- **exact position — off until turned on**

Exact position is the one thing here that lets somebody find a person rather than know about them. A
privacy default that has to be turned off is not a privacy default.

An empty audience is not the same as an absent entry: absent means "the permission's default", empty
means "explicitly nobody". Somebody who turned a disclosure off has to stay off if the default ever
changes. And a disclosure is always shared with ourselves, or the local HUD showing our own position
would be gated on us agreeing to share it with somebody.

The service is built before anything that could send data anywhere — the gate has to exist before
there is something to gate.

---

## When this, then that

The plan wants an engine behind achievements, soundboard triggers, contracts, evidence automation,
cosmetic rewards, titles and notifications. All seven are the same shape — watch for something, check
some conditions, do some things — and writing them seven times is how a mod ends up with seven
slightly different ideas of what a cooldown means.

```kotlin
context.rules.register(
    Rule(
        id = SqId.sidequest("achievement.hyperion"),
        displayName = "Big spender",
        trigger = RuleTrigger.of<RareDropEvent>(),
        condition = Condition.all(
            Condition.ItemIs(setOf("HYPERION")),
            Condition.OnIsland(setOf(Island.CRIMSON_ISLE)),
        ),
        tiers = listOf(1, 5),
        actions = listOf(
            RuleAction.AddProgress(),
            RuleAction.Notify("{rule} · tier {tier}", "that is {progress} of them"),
            RuleAction.PlaySound(SqId.sidequest("sound.fanfare")),
        ),
    ),
)
```

### A rule is data

There is no code in one beyond its conditions and actions, and both of those are values. That is what
makes a rule inspectable: `/sqrule show <id>` can print it, `/sqrule fire <id>` can run it on demand,
and a rule that arrives from the backend can be checked before it is trusted rather than being a piece
of code somebody has to take on faith.

### A skip is explained, never swallowed

`Condition` is a sealed tree rather than a lambda, and this is the reason. A lambda is opaque: when a
rule does not fire, all anybody can say is "the condition was false". A tree can be walked, so
`Condition.explain` names *the branch that failed* — and "which condition stopped it" is the only
question anybody ever asks about a rule that is not working.

```
sidequest:achievement.hyperion  on the Hub
sidequest:contract.debt         progress 2, next tier 3
sidequest:soundboard.laugh      on cooldown for another 4200ms
```

Every evaluation produces one of those, and the last few hundred are kept in `rules.trace()`, which is
what `/sqrule trace` prints. They are logged at TRACE and not DEBUG on purpose: a skip happens on
nearly every event, and at DEBUG they would drown everything else.

### An unhandled action is skipped, not fatal

Actions are data dispatched by `kind` to an `ActionHandler` the engine looks up, and the engine imports
no subsystem — the mod registers the handlers in `SidequestPlatform`. Today that is `notify`, `sound`,
`sound_pool` and `stat`; `currency`, `evidence`, `cosmetic`, `cinematic` and the rest have no subsystem
yet. A rule naming one of those **does the half that works** and logs the rest. Half-supported beats
broken, and it is what lets the engine exist before the things it will eventually drive.

### Tiers, and why they are not three rules

"Kill 10, 100, 1000" is one rule and three achievements. Modelled as three rules it is three copies of
one condition that can drift apart, so instead a rule carries `tiers` and fires each time progress
crosses one. Progress is added *before* the check, because a tier is a threshold on the new total, and
the **highest** newly-crossed tier wins: progress that jumps from 0 to 150 past tiers of 10 and 100 has
earned the 100, and announcing the 10 would be announcing the wrong thing. `{tier}`, `{progress}` and
`{firings}` are substituted into an action's text, so one action serves every tier.

### State is per subject

Most rules are about the local player, but "Alice has paid three debts" is the same machinery with a
different subject, and building it for one player means rebuilding it later. Cooldowns are per subject
too: a rule about a party member's drops should not go quiet because a different member triggered it a
second ago.

A null subject means *the subject the engine would have picked itself* — the local player — everywhere.
That cost a real bug. An event files progress under the local player, so `progressOf(id)` without a
subject returned zero and `/sqrule list` showed every rule at nothing. Every headless test passed an
explicit subject, so none of them noticed; a real client run did. Clearing everybody is now its own
method, `resetEverySubject`, because a default that means two things is exactly what went wrong.

### Which conditions exist

Typed today: island, activity, activity confidence, party state, custom friend, chat phrase, item (by
SkyBlock id *or* display name, because a drop read from chat has only a name), numeric value, own or
another rule's progress, whether another rule has fired, and a window since one last fired.

The plan also lists death cause, debt state, ping response, current title and current cosmetic. Each of
those needs a subsystem that does not exist yet, and inventing a condition against a data model nobody
has written is how migrations get made. `Condition.Custom` covers them in the meantime and is honest
about it: it names itself in `explain` and says nothing more, because a predicate cannot be walked.

### Cost

Rules are indexed by their trigger's event class, and the index is walked up the event's class
hierarchy so a rule on `ChatDerivedEvent` sees a `RareDropEvent` without being registered under each.
A hundred rules evaluating their conditions on every event at twenty ticks a second is a lot of work to
conclude nothing. Cheap checks come first inside an evaluation too — a firing limit and a cooldown are
two map lookups, and most evaluations end there.

The engine takes one `IMMEDIATE` subscription on `SidequestEvent` rather than one per rule, which would
have to be torn down and rebuilt every time a rule was registered. `RuleFiredEvent` is excluded from
it: a rule triggered on one and firing would trigger itself, and one recursion is all it takes.

Progress is persisted per account and written on a ten-second timer rather than per change, because a
rule that adds progress on every kill would otherwise write the file twenty times a second.

---

## Seeing what the mod is doing

Everything here is layered behind an interface, which is what makes it testable and also what makes it
invisible. A notification that does not appear could be a switched-off category, a deduplication, a queue
waiting for a safe moment, a missing sink, or a bug — and from outside all five look identical. These commands
are how the difference gets seen.

| Command | |
| --- | --- |
| `/sqstatus` | one screen of everything: location, activity, party, backend, parsers, players, permissions |
| `/sqlog <category> <level>` | turns a log category up or down at runtime; `/sqlog all debug` |
| `/sqtest <what>` | fires a subsystem: `notify` `sound` `queue` `presence` `chat` `item` `rule` |
| `/sqrule [list\|show\|fire\|reset\|trace] [id]` | lists rules with their progress, prints one, fires one on demand, or shows why recent ones did not |
| `/sqdiag` | ticks, joins, uptime |
| `/sqchat` | toggles chat tracing and prints the parser's counters |
| `/sqboard` | dumps both boards and both readings, with `§` as `&` |

`/sqstatus` prints **both** `busy` and `demanding`, because they answer different questions and conflating
them has already caused one bug — see [Activity](#activity).

`/sqtest notify` reports the *outcome* of each notification rather than "sent", for the reason above:
`DISABLED`, `QUEUED`, `COMPACT` and `BOTH` are four different explanations for an empty screen.

`/sqtest sound` plays one sound per volume group plus a pool three times and a deliberately broken id, so a
mute, a volume setting and the fallback path can all be heard rather than inferred. Vanilla sounds only —
testing the manager should not also require a working asset pipeline.

`/sqtest queue` uses serious mode to stand in for being busy, so the hold-and-release path can be exercised
without entering a dungeon.

`/sqtest item` reads whatever is in hand, which is the one thing no headless test can do.

`/sqrule fire <id>` is the plan's manual admin trigger. It is the only way to check a rule about something
nobody can arrange — a rare drop is not something a developer can produce on demand — and it reports the
*reason* when nothing happens, so an unfired rule names the condition that stopped it. The event it passes is
a stand-in, and the command says so: a condition about text or an item cannot hold against an event carrying
neither, so a skip there is not proof the rule is broken.

`/sqtest rule` drives a tiered test rule through the **live bus** rather than calling the engine, which is the
part most likely to be wrong. A rule that fires when invoked directly and never fires in play is exactly the
bug that catches.

### Log levels at runtime

`/sqlog` exists because the alternative is a rebuild to change a level, which in practice means nobody
changes one and the debug lines that were carefully written are never read. Categories are `PLATFORM`,
`FEATURE`, `EVENT`, `PARSER`, `PERSISTENCE`, `BACKEND`, `REALTIME`, `ASSET`, `RENDER`, `AUDIO`.

What is worth turning up:

- `PARSER` at `DEBUG` — every scoreboard line that appeared or went, and every tab widget that came or went.
  A pattern that stopped matching is almost always a line that changed, and this is where that shows.
- `PARSER` at `TRACE` — the lines themselves, and a once-a-minute board-poll heartbeat so a session that
  *looks* frozen can be told from one that is.
- `BACKEND` / `REALTIME` at `DEBUG` — every state transition, every refusal, every reconnect.
- `FEATURE` at `DEBUG` — why each notification was dropped, held, or shown.

Island and activity changes are at `INFO` and always on. The activity line carries its *reason*, because a
wrong activity is the most likely thing to be reported and "what made you think that" is the only useful
first question.

### Notification actions are offered in chat, not on the toast

A HUD toast **cannot be clicked**, and this is not a missing wire. Nothing delivers input to the live HUD
layer, and during gameplay the cursor is grabbed — there is no pointer, and a click swings the player's sword.
`NotificationRegionNode` does handle a pointer press, which is what makes this easy to get wrong: the code
looks connected.

So a notification with actions also produces a chat line with one clickable label per action, running a hidden
`/sqaction` client command. Chat is the only surface that is clickable while the cursor is grabbed, it persists
so somebody who was looking at their inventory can still act, and the whole path already existed — `SqText`
carries a click action and the commands are real Fabric client commands, so the click is intercepted
client-side rather than sent to Hypixel.

The toast is left explicitly non-interactive. Marking it interactive would make it hover and highlight as
though it could be pressed, which is worse than plain: it would look broken rather than look like a message.

`/sqaction` is registered by the *platform*, not by a feature, because a notification's actions have to work
whatever is switched on.

### Three bugs the in-game test caught

`DeveloperToolsTest` drives every one of these commands on a real client, and found three failures that no
headless test could:

**The mod crashed on startup.** The notification sink asked the platform for a logger, and the platform took
the sink as a constructor argument — a `lazy` cycle, `StackOverflowError`, dead before the main menu. The
logger is now resolved on use.

**Every notification threw.** Platform notification ids are UUIDs; `UiId` paths are `[a-z0-9_]` and reject a
hyphen. Both rules are right for their own type, and the translation between them was missing.

**Rule progress read as zero.** The engine fired correctly, twice, and `progressOf(id)` returned nothing —
because an event files progress under the local player while a null subject meant "nobody in particular". The
432 headless rule tests all passed an explicit subject, so none of them could see it. A null subject now means
the local player everywhere, and clearing everybody has its own method.

None is subtle in hindsight. All three are exactly what happens when two layers that were each tested alone
meet for the first time.

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
network comes back out as a typed event. `ItemReaderTest`: that a stack shaped the way Hypixel
builds one is read out of the components it actually lives in. `PlayerTargetingTest`: that the
raycast and the line-of-sight test run against a real level at all. `StorageAndPermissionsTest`: that
the directory the mod really writes to exists and is writable, and that the privacy defaults hold on
the service the mod really built.

`PlayerTargetingTest` earned its place immediately. It asserted that the local player is in the
directory, which failed — nothing was feeding the online player list in, and every headless test
passed. The gap was a whole missing poll, and no fake could have shown it.

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
- parse raw scoreboard or tab-list text — read `gameContext`, or `TabWidget` for a widget
- persist, cache or pass around an `ItemStack` — snapshot it as an `SqItem`
- watch for party chat lines — read `party`
- store a player by username — store a `PlayerId`
- read or write a file — use `store(...)`
- send or reveal anything without asking `permissions`
- make an HTTP request — submit through the backend client, which queues when offline
- match a regex against a chat line — declare a `ChatRule` instead
- perform HTTP requests or open WebSockets
- download remote assets
- resolve a player by username alone for anything durable

Each of those has, or will have, a service. The first is enforced by the compiler; the
rest are enforced by review, and by the fact that doing it the wrong way is more work.
