# Discord rich presence

What the mod puts on your Discord profile, and what it takes to make the pictures appear.

## Turning it on

**Settings → Network → Discord**, and flip the switch. That is the whole of it.

Sidequest ships its own registered Discord application (`1533887312373616650`), so nobody in the group types
anything — the same reasoning as the backend URL being defaulted to the group's own server. The application
id is still a setting, for anybody who wants the card to carry a name of their own, but it is an override
rather than a step.

It stays **off by default** even so. Everything else in the mod is shared with people who opted into a
backend; this is shared with everybody who can read a Discord profile, and that should be a choice somebody
makes rather than one made for them by an update.

**Uploading art is optional** — every line of text stands on its own, and an image key Discord does not
recognise simply shows no image.

### Two things about the application itself

Both easy to forget, because neither lives in this repository:

- **Its name is the bold first line of every card the mod draws.** Renaming it in the developer portal
  renames the presence for everyone, with no rebuild.
- **The art keys below resolve against *this* application's library.** Art uploaded to any other application
  will not appear, however correctly it is named.

## What it shows

Two lines, two pictures and a clock.

| | Source |
|---|---|
| First line | What you are doing — `Mining`, `Dungeons · Floor 7`, `Kuudra · Tier 4` |
| Second line | Where you are, and how many with — `Dwarven Mines · Royal Mines · Party of 4` |
| Large image | The island |
| Small image | The activity |
| Clock | Counts from when you joined Hypixel |

Each of those is a separate switch, and each switch gates the **picture as well as the words**. Hiding the
island hides the island's photograph too, which is the whole reason the switches are checked in one place.

### Party members are counted, never named

There is no setting that puts another player's name on your profile, and no code path that could. Naming
somebody would publish *their* whereabouts to an audience they have no relationship with — your Discord
friend list is not their friend group.

### This is more public than anything else in the mod

Waypoints, pings, presence and debts all travel to a backend the group opted into. This travels to everybody
who can read a Discord profile, including people who have never heard of SkyBlock. The privacy switches are
separate from the group's permissions for exactly that reason.

Off by default: your SkyBlock **profile name**, because people name profiles things they would not choose as
a public label.

## Uploading the art

In the Discord developer portal: **Rich Presence → Art Assets**. The key is the file's name in that list.

Islands — the large image:

```
island_private_island       island_private_island_guest  island_hub
island_dark_auction         island_winter                island_farming_1
island_garden               island_garden_guest          island_mining_1
island_mining_2             island_mining_3              island_crystal_hollows
island_mineshaft            island_fishing_1             island_lotus_atoll
island_foraging_1           island_foraging_2            island_foraging_3
island_combat_1             island_combat_3              island_crimson_isle
island_dungeon_hub          island_dungeon               island_kuudra
island_rift                 island_safari
```

Activities — the small image, overlaid on the corner:

```
activity_dungeons   activity_kuudra    activity_mining     activity_garden
activity_farming    activity_fishing   activity_foraging   activity_slayer
activity_rift       activity_crimson_questing             activity_forge
activity_auction    activity_exploring activity_idle
```

And `sidequest`, used whenever there is nothing more specific to show — outside SkyBlock, or when the island
is one you have chosen not to publish.

The keys are **derived from `Island.serializedId` and the `Activity` enum**, not from a table someone has to
keep in step. Hypixel's own identifiers are the island half, which is why they read as `mining_3` rather than
`dwarven_mines`. There is deliberately no key for an unknown island or an unknown activity: those are answers
about *not knowing*, and they are never published.

## When nothing appears

Run **`/sqrpc`**. It reports which of the several identical-looking failures this is:

- **Off** — the switch.
- **No application id** — the field.
- **Not connected** — Discord is not running, or its socket is not visible from this process.
- **Connected, showing nothing** — you are not on Hypixel.
- **Connected** — with the two lines it is currently showing, so you can see whether the privacy switches
  composed it down to nothing.

### Flatpak

If the launcher runs under Flatpak, Discord's socket lives outside the sandbox and the mod cannot see it —
which looks exactly like Discord being closed. `/sqrpc` says so specifically, and the fix is to grant access:

```
flatpak override --user <launcher> --filesystem=xdg-run/discord-ipc-0
```

### arRPC and other bridges

Anything that presents a `discord-ipc-*` socket works, not only the official client — Vesktop and other web
clients typically use [arRPC](https://github.com/OpenAsar/arrpc) to provide one. Worth knowing: **arRPC does
not validate the application id**, so a typo that the official client would reject as an error connects
cleanly and then shows nothing recognisable. If the presence connects but looks wrong, check the id.

## For the next person to touch this

The protocol lives in `platform-core`, under `platform/core/presence`:

- `PresenceComposer` — game context in, presence out. Pure, and where every privacy decision is made.
- `DiscordFrames` — the wire format. `[opcode int32 LE][length int32 LE][payload UTF-8]`.
- `DiscordIpcClient` — handshake and `SET_ACTIVITY`, over an injected pipe.
- `DiscordSockets` — finding a running Discord, including sandboxed installs.

`DiscordIpcTest` checks all of it against a Discord made of bytes. That suite cannot catch a wire format that
is self-consistently wrong — it was written from the same understanding as the code — so there is also
`DiscordLiveTest`, skipped unless you ask for it. Run it after touching the framing, the socket discovery or
the activity payload:

```
SIDEQUEST_DISCORD_LIVE=1 SIDEQUEST_DISCORD_APP=1533887312373616650 \
  ./gradlew :platform-core:test --tests '*DiscordLiveTest*' --rerun-tasks
```

Two tests, deliberately different in kind. The first hands Discord an application id that *cannot* exist,
because a rejection proves the frame was decoded while changing nothing about your presence. The second
sends a real activity under the real application and checks it comes back without an `ERROR` — it briefly
shows a card on the running account's profile, then clears it.

Three rules that are not obvious from the code:

- **Timestamps are epoch seconds, not milliseconds.** Confirmed live: sending `1786711130` comes back echoed
  as `1786711130000`, so Discord reads seconds and normalises. Milliseconds would produce a clock counting
  from the year 55000 — a plausible-looking number, wrong by fifty thousand years.
- **Nulls must be absent, not null.** The payload is encoded with `explicitNulls = false` for that reason;
  an explicit null where Discord expects a missing field is rejected.
- **Every IPC call blocks and belongs on the background thread.** A Discord that has stopped reading its own
  socket is not a rare state, and a blocking write from the client thread takes the game with it.
