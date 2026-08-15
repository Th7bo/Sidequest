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

## Failure behaviour

The native screen opens immediately in a loading state. It gives a useful message for an unpaired backend,
a missing server API key, an unknown player or profile, private API data, and an upstream rate limit. Network
work runs away from Minecraft's client thread, and closing the screen makes late results harmless.

Press `Ctrl+O` to open the same player and profile on SkyCrypt in the system browser. `SkyCryptUrls` exists
only for that explicit fallback and username validation; it is not part of the native data path.
