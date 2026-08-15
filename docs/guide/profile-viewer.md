# Profile viewer

Somebody's SkyBlock stats, without alt-tabbing. Use `/sqprofile chrooted`, or choose **View SkyBlock
stats** in the player menu.

The viewer is a native Sidequest screen. It does not load a web page, bundle Chromium, or require another
mod. The data comes from Hypixel's official API through the Sidequest backend; the Hypixel application key
stays on the server and is never written to the mod jar or a player's configuration.

## Setup

Set `SIDEQUEST_HYPIXEL_API_KEY` on the backend. Clients must be paired with that backend because the profile
endpoint is authenticated; this prevents the public server URL becoming an unauthenticated proxy for the
group's API allowance.

The backend caches resolved Minecraft identities, skill definitions, and rendered profile summaries. Five
people opening the same profile therefore consume one upstream lookup rather than five. Hypixel rate-limit
responses are passed back as a temporary failure instead of being retried in a tight loop.

## Profiles and levels

`/sqprofile <player> [profile]` chooses the selected profile when the second argument is omitted. A supplied
profile name matches Hypixel's `cute_name` case-insensitively. The screen keeps the command's recent-player
list, so tab completion and quick switching use the same names.

Skill experience is read from the member's public profile data. Level thresholds come from Hypixel's public
`/v2/resources/skyblock/skills` resource and are cumulative; Sidequest does not ship a copied XP table.
Fields hidden by a player's in-game API settings are shown as unavailable rather than as zero.

## Tabs and data coverage

The screen is split into item-backed tabs rather than one long report. The icons are real Minecraft item
models, so the active resource pack styles them too.

- **Overview** — profile identity, level, balances, fairy souls, magical power, first join, profile switcher,
  aggregate kills/deaths and currencies.
- **Skills** — every skill Hypixel returns, its official cap, total XP and progress through the current level.
- **Combat** — Slayer XP/levels/kills, dungeon types/classes/floor completions, Bestiary and Crimson Isle.
- **Inventory** — availability summaries for inventory, armour, wardrobe, equipment, bags and shared
  inventories; sack item counts; and public loadout data.
- **Collections** — every public collection and its amount.
- **Pets** — every public pet, rarity, XP, active state, held item/skin identifiers and candy use on the wire.
- **Mining** — Heart of the Mountain, powders, crystals, Glacite Tunnels, forge and skill-tree progression.
- **Farming** — Garden, Jacob's contests, plots, visitors, crops and other public Garden progress.
- **Fishing** — trophy fish counts and tiers.
- **Foraging** — Foraging core, skill progression and attributes.
- **Rift** — public Rift progression and collectible data.
- **More** — Museum, essence/currencies, experimentation, leveling, objectives, quests, events, shards,
  temples, safari/hunting and every other bounded displayable profile category returned by Hypixel.

Inventory item stacks are different: Hypixel encodes them as compressed NBT blobs. The viewer reports which
containers are public and shows the separately exposed sack counts, but does not copy opaque NBT wholesale
into every summary response. Detailed item rendering belongs behind an on-demand inventory fetch so opening
the broad profile viewer stays quick.

Open-ended objects are flattened defensively with per-section limits. Unknown nested objects and newly added
API fields cannot crash the whole lookup; displayable numbers, booleans and short labels are included while
large binary strings are ignored.

## Failure behaviour

The native screen opens immediately in a loading state. It gives a useful message for an unpaired backend,
a missing server API key, an unknown player or profile, private API data, and an upstream rate limit. Network
work runs away from Minecraft's client thread, and closing the screen makes late results harmless.

Press `Ctrl+O` to open the same player and profile on SkyCrypt in the system browser. `SkyCryptUrls` exists
only for that explicit fallback and username validation; it is not part of the native data path.
