# Discord rich presence

What the mod puts on your Discord profile, and what it takes to make the pictures appear.

## Turning it on

**Settings → Network → Discord.** It needs two things: the switch, and an application id.

There is no default application id and there cannot be one. An application id names a *registered Discord
application*, and that application's name is the bold first line on the presence card — its art library is
also what every image key below resolves against. Shipping one would file this group's activity under
somebody else's application name and somebody else's pictures.

To get one:

1. Go to <https://discord.com/developers/applications> and create an application.
2. Name it whatever should appear as the bold line — `SkyBlock`, `Hypixel SkyBlock`, `Sidequest`.
3. Copy the **Application ID** (all digits) into the setting.

That is the whole of the setup. **Uploading art is optional** — every line of text stands on its own, and an
image key Discord does not recognise simply shows no image.

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
is self-consistently wrong, so there is also `DiscordLiveTest`, skipped unless you ask for it:

```
SIDEQUEST_DISCORD_LIVE=1 ./gradlew :platform-core:test --tests '*DiscordLiveTest*'
```

Run it after touching the framing or the socket discovery. It hands Discord an application id that cannot
exist, because a *rejection* proves the frame was decoded while changing nothing about your real presence.

Two rules that are not obvious from the code:

- **Timestamps are epoch seconds, not milliseconds.** Milliseconds produce a clock counting from the year
  55000, which renders as a plausible number and is wrong by fifty thousand years.
- **Every IPC call blocks and belongs on the background thread.** A Discord that has stopped reading its own
  socket is not a rare state, and a blocking write from the client thread takes the game with it.
