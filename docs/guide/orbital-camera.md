# Orbital camera

Looks around without turning you. `/sqorbit` to toggle, `/sqorbitcentre` to put the camera back behind you.
Settings under **Gameplay** → **Garden**.

What F5 gives you is the view from behind. What this gives you is that view *and* a mouse that moves the
camera instead of the player — so a farming run can be watched from the side, from above, from wherever,
while the row being cut never changes direction.

## Working with a mouse lock

It is built to sit alongside one rather than compete with it.

SkyHanni's mouse lock exists so a knocked mouse does not ruin a run, and it works by zeroing the rotation
the mouse would have applied — it wraps the call that turns the player and multiplies the deltas by nothing.
This takes the movement one step earlier, at the top of the same method, and cancels that method outright.

The consequence is the one that matters: with the lock on, this is the only thing reading the mouse, and
with the lock off it still is. The wrapper never runs because the method containing it never runs. Neither
mod needs to know the other exists, and the order the game happens to load them in cannot change the
outcome. Two mods reaching for the same call and depending on the order would be a bug that only shows up on
somebody else's install.

## What moves and what does not

Only the view. Your player keeps facing the crops, your reach still goes where you are pointing, and the
block you break is the one you were already going to break.

Movement keys stay relative to your body, not the camera — walking forward with the camera swung round to
the side still walks the way you are facing. That is deliberate: the point is to keep farming in a straight
line while looking somewhere else.

## Starting itself

Optional, and off by default. Turn it on and the camera arrives once you have broken a run's worth of blocks
without stopping — 150 by default, adjustable.

Blocks rather than seconds, because that is what separates farming from everything else somebody does with a
block: clearing a path is four, a run is hundreds. A gap of a few seconds ends the run, and ending the run
puts the camera away again. That second half is what makes it bearable — a camera that turned itself on and
then stayed on would be worse than one that never did.

`/sqorbit` during a run it started means *not this run*, not *never again*. It stands down until the run ends
rather than switching back on with the next block, which is the same rule the perspective override follows.

## Pests put it away

A pest spawning turns the camera off. On by default, and the one part of this that is: a pest has to be
found and killed, and doing that behind a camera pointed somewhere else is worse than having no camera help
at all.

It ends the run outright rather than standing down, so going back to farming earns the camera again the same
way it did the first time. It stops a camera you turned on by hand too — "stop when pests spawn" is a claim
about the pest, not about how the camera got there.

The line it watches for is Hypixel's own, and two things about it are invisible to somebody reading it in
game: the gap after the article is *two* spaces, where an icon was stripped, and the pattern is anchored so
that a party member quoting the message does not stop your camera. Both carry a test that fails without them.

## Scope and limits

- **The Garden only.** A camera that stops the mouse turning you is a camera that gets somebody killed in a
  dungeon. Leaving the island turns it off and says so.
- **Your perspective is borrowed.** Whatever view you had comes back when the mode ends. Change perspective
  yourself while it is on and it hands it back for good rather than fighting you once a tick.
- **The camera stops just short of straight up and down.** Vertical is a singularity for a camera built from
  a yaw and a pitch — passing through rolls the view, which reads as the world spinning.
- **Sensitivity and inversion are its own**, separate from the game's. Swinging a camera slowly across a
  field and flicking to face a crop want different numbers, and one setting cannot be right for both.

## What is tested

The arithmetic, which is the part that goes wrong quietly: the pitch limit accounting for where the player is
already looking rather than clamping the offset by itself, yaw wrapping instead of growing until a float
loses its precision, and inversion touching the vertical only. Eight tests. The two mixins are four lines
each and need a game to exercise.
