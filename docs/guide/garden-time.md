# Garden time reminder

Night before pests spawn, Day before you kill them, Night again for the next lot. The mod keeps track so you
do not have to. On by default; settings under **Gameplay** → **Garden**. `/sqgardentime` says what the time
is and what it should be.

## What it reads

Only Hypixel's own chat lines, anchored so a quoted copy in party chat changes nothing:

- `Your garden time has been set to Day!` / `...Night!` — the current time.
- `GROSS! A Pest has appeared in ...` / `YUCK! 4 Pests have spawned in ...` — pests out.
- `You received 7x Enchanted Potato for killing a Locust!` — a pest died.

## What it says

| Moment | What you get |
| --- | --- |
| Farming, no pests out, time is Day | Reminder to set Night — once as the run starts, then every 90 seconds |
| Pests spawn at Night | Title and toast: set Day before killing them |
| Pests spawn at Day | Toast: Night was missed this time |
| Day set with pests out | Toast: go get them |
| Night set with pests still out | Title and toast: set it back to Day |
| **Pest killed at Night** | Red title, alarm, chat line, urgent toast |
| Last pest killed at Day | Toast: back to Night before the next spawn |

The sounds can be turned off separately from the reminders.

## What it cannot know

- **The time after a fresh login.** Until you set it once, the mod has not seen it. While farming it asks
  once per run rather than nagging, and a pest killed with the time unknown sets off nothing.
- **Exactly how many pests are out.** The count comes from spawn and kill lines. Pests that were already out
  before the mod saw them still count as kills, and the count never goes below zero.
- **Somebody else's Garden.** Reminders while farming are for your own Garden only.
