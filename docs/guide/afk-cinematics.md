# AFK cinematics

The camera a big game shows when you put the controller down. Leave the keyboard for a few minutes and the
view leaves your eyes: bars slide in top and bottom, and the game starts cutting between shots of wherever
you are standing. Touch anything and it hands the view straight back.

`/sqafk` starts it now, or puts it away. Settings under **Alerts** → **AFK camera**.

## When it starts

Three minutes by default, adjustable from fifteen seconds to a quarter of an hour. That is the setting most
people will want to change and it is the first one in the section.

Being away means no sign of life at all, and the client watches for more than the obvious one:

- **You moved.** Position, checked against a threshold rather than against zero — a standing player's
  position wobbles by fractions of a block from collision resolution alone, and a mod that treated that as
  movement would never decide anybody was away.
- **You looked.** Reading chat without walking anywhere still turns the view, and a camera that took over
  while somebody was mid-sentence would be indefensible. A fifth of a degree is enough.
- **You broke a block.** An autoclicker on a crop barely moves the player, and that is somebody's session
  running.

A loading screen counts as a sign of life rather than as idle time. Time spent with no world is not time
spent away from the keyboard, and counting it would start the camera the moment you finished loading in.

## When it refuses

It asks the same question the rest of the mod asks before covering your screen, and gets the same answer —
the safety gate that stops a rare-drop cinematic playing at a boss. There is one definition of "do not
interrupt" in Sidequest and this feature is not allowed to have its own.

So it will not start while you are dead, on low health, taking damage, mid-run in something demanding,
somewhere hazardous, with a screen open, with another cinematic playing, with serious mode on, or with
cinematics switched off entirely.

**The same gate is checked while it runs, not only before it starts.** Being away is not a promise that
nothing will happen: something wanders up and hits you on a public island and the camera is gone before you
are. That check is the difference between a toy and something you can actually leave on.

## The shots

Seven, and they are written out rather than generated, because a random angle is not a shot — half of what
makes a camera look deliberate is that somebody chose the height and the side.

| | |
| --- | --- |
| Establishing | High and over the shoulder, drifting round |
| Orbit | The long one: a full sweep past both sides at close to eye level |
| Front | In front of you, looking back |
| Low angle | Below and in front, looking up |
| Overhead | Almost straight down, turning slowly |
| Profile | A short one from the side — the beat between two longer moves |
| Skyline | Low, looking up past you at whatever the sky is doing |

Each move eases in and out, which is the whole difference between a camera move and a rotation: a linear
sweep starts and stops at full speed and reads as the view being dragged.

Between shots it **cuts**. A cut is free and reads as an edit; a continuous sweep round the player reads as a
bug. The same shot never runs twice in a row.

One setting controls the length of all of them, and the shots keep their relative lengths at every setting —
the long orbit stays longer than the quick profile whether you set three seconds or twenty. Everything
converging on one length would flatten the rhythm, which is most of what makes a reel feel edited.

## Coming and going

The two ends are the only part that is not a cut. The camera eases out from exactly where you left it, and
eases back to exactly there — about two thirds of a second each way, with the bars on the same ramp so the
two read as one gesture rather than as two things happening at once.

That is what makes it safe to hand the view back the instant you touch anything: you never come back to a
camera that has to jump home.

## Scope and limits

- **Your perspective is borrowed.** It needs third person, and whatever view you had comes back afterwards.
  Change perspective yourself while it is running and it ends rather than fighting you once a tick.
- **`/sqafk` while it is running means *not this time*, not *never again*.** It stands down until your next
  sign of life, rather than starting again a quarter of a second later because you are, of course, still
  standing still.
- **The orbital camera wins.** Both cannot point the view at once, and the one that gives way is the one
  nobody asked for.
- **The camera only turns; it does not move.** Shots are angles around you at the game's own third-person
  distance, so the camera still collides with walls and pulls in the way the game's does. There is no dolly.
- **Shots stop short of straight up and down.** Vertical is a singularity for a camera built from a yaw and a
  pitch — passing through rolls the view, which reads as the world spinning.

## What is tested

The half that can hurt somebody: deciding that a person is away. Standing still counting up, walking and
looking resetting it, a standing player's wobble not counting, a yaw crossing zero being two degrees rather
than three hundred and fifty, and a loading screen not banking idle time.

Then the reel, where the assertions worth reading are the ends — that the camera leaves from exactly where
the player left it and returns to exactly there, and that the exit is continuous from wherever the reel had
got to. The no-repeat rule is tested against a picker deliberately built to want to repeat: draw-and-reject
would loop forever on it, which is why the shot is chosen from the others directly.

The mixin is a few lines and needs a game to exercise.
