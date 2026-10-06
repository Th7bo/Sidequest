# Garden time reminder

Farm at Day, switch to Night just for the pest spawn, back to Day before killing anything — and leave one pest
on the plot for the next wave. The mod keeps track so you do not have to. On by default; settings under **Gameplay** → **Garden**. `/sqgardentime` says what the time
is and what it should be.

## What it reads

Only Hypixel's own chat lines, anchored so a quoted copy in party chat changes nothing:

- `Your garden time has been set to Day!` / `...Night!` — the current time.
- `GROSS! A Pest has appeared in ...` / `YUCK! 4 Pests have spawned in ...` — pests out.
- `You received 7x Enchanted Potato for killing a Locust!` — a pest died.

And the tab list's **Pests** widget (turn it on with `/widget`), for ` Cooldown: 1m 58s` / `READY` /
`MAX PESTS` — the same lines SkyHanni's pest timer reads.

## What it says

| Moment | What you get |
| --- | --- |
| Pest cooldown nearly over, time not Night | Title and toast: set Night — once, then every 15 seconds while farming |
| Pests spawn at Night | Title and toast: set Day before killing them |
| Still farming at Night after the spawn | Reminder to set Day — once as the run starts, then every 90 seconds |
| Pests spawn at Day | Toast: Night was missed this time |
| Day set after the spawn | Toast: kill all but one |
| **Pest killed at Night** | Red title, alarm, chat line, urgent toast |
| Down to the last pest at Day | Toast: leave it for the next wave |

How early "set Night" comes is a setting: 10 seconds before the cooldown ends by default, 0 to 60. Nothing
is said at max pests, since nothing can spawn.

Setting Night ahead of a spawn — with the kept pest alive, farming while you wait — is never nagged about.

The sounds can be turned off separately from the reminders.

## What it cannot know

- **The time after a fresh login.** Until you set it once, the mod has not seen it, so it reminds about
  nothing and a pest killed with the time unknown sets off nothing.
- **Exactly how many pests are out.** The count comes from spawn and kill lines. Pests that were already out
  before the mod saw them still count as kills, and the count never goes below zero — so after a reconnect
  the "last pest" toast may not show.
- **Somebody else's Garden.** Reminders while farming are for your own Garden only.
