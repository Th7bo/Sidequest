# Profile viewer

Somebody's SkyBlock stats, without alt-tabbing. `/sqprofile chrooted`, or **View SkyBlock stats** in the
player menu.

It opens their [SkyCrypt](https://sky.shiiyu.moe) page — the same page a browser would show, because it *is*
a browser.

## Two ways it can open

| | When |
|---|---|
| **A window inside the game** | The `mcef-modern` mod is installed and **Open inside the game** is on |
| **Your own browser** | Otherwise — no mod, or you prefer a real window |

Neither is a degraded mode. A second monitor beats a window inside Minecraft, which is why the preference
exists rather than the in-game view being forced whenever it is possible.

**MCEF is optional and not bundled.** It downloads a couple of hundred megabytes of Chromium the first time
it starts, and nobody should pay that to use the waypoints. Install it from
[Modrinth](https://modrinth.com/mod/mcef-modern) if you want the in-game window:

- Minecraft **26.2** → `0.3.3+mc26.2`
- Minecraft **26.1.2** → `0.3.2+mc26.1` (0.3.3 dropped its 26.1.2 listing)

The first open on a fresh install shows a progress panel — downloading, extracting, installing, starting.
That is Chromium arriving, and it happens once.

## The window

The page sits in a frame the mod draws, rather than filling the screen: a bar across the top with the title,
the attribution, a search box for an IGN and the window controls, and the page inset below it. The expand
button drops the frame for a full-bleed view; clicking the dimmed surround closes.

Typing a name into the box and pressing enter loads that player without reopening the screen. It accepts
only the characters a Minecraft name can contain, and the name is still validated on submit — the box is a
second door into the same house, and a name typed there never passed through the command.

### Quick switch

The `‹ ›` arrows step through **recent lookups first, then your friends**, wrapping at the ends. One list
rather than two: a "recents" control and a "friends" control side by side would be two places to press for
the same thing, and the person you looked at a minute ago and the person on your friend list are both just
"somebody I want to see again". The same list is what `/sqprofile <tab>` completes from.

Recents are capped at eight, deduplicated case-insensitively — Minecraft names compare that way, and the
same person typed two ways is not two people. They live in the settings file and can be pruned there.

The arrows collapse to **zero width** rather than disappearing when there is nowhere to go, so the rest of
the bar does not jump sideways the first time you look up a second player.

The chrome lives in `ui-components` as `ProfileWindowChrome`, deliberately apart from the screen. The screen
is the only thing that can hold a browser and a browser is the one thing a test cannot have, so everything
*except* the page renders to a PNG without a game:

```
SIDEQUEST_PROFILE_PREVIEW=/tmp/window.png \
  ./gradlew :ui-components:test --tests '*ProfileWindowPreview*' --rerun-tasks
```

That is not decoration. The first render of the expand button came out as a *circle* — `radii.small` is five
units, which on an eight-unit box is most of the way round — and it read as a radio button. A corner radius
that is a fixed fraction of its shape has to be written as a literal, and it took a picture to notice.

`ProfileWindowLayoutTest` covers what a picture does not: that the content clears the frame's rounded
corners, that the search box gives way on a narrow window instead of swallowing the title, and that nothing
overlaps at any width.

## Keys

Everything is a key rather than a toolbar, apart from the three controls in the bar:

| Key | |
|---|---|
| `Esc` | Close, or leave the search box |
| `Ctrl` `L` | Jump to the search box |
| `Ctrl` `O` | Open this page in your real browser |
| `F5` | Reload |
| `Alt` `←` | Back |

**The shortcuts take a modifier on purpose.** An earlier version claimed bare `O`, `F5` and `Backspace`,
which is fine right up until somebody types into SkyCrypt's own search box and the letter `o` opens their
desktop browser. Anything that could be a character has to reach the page.

`Esc` is handled before the page sees it, deliberately — a web page must not be able to swallow the only key
that gets you out of it.

## Sharpness and size

The browser is created at the **window's framebuffer size, not the screen's GUI-scaled size**, and that
distinction is the difference between a sharp page and a blurry one.

A `Screen`'s `width` and `height` are GUI units: at GUI scale 3 on a 1920×1080 window they are 640×360.
Building the browser at that size and blitting it across the whole window does two bad things at once —
Chromium lays the page out for a 640-pixel viewport, so it picks its narrow layout, and then that layout is
magnified threefold. "Too zoomed in" and "pixelated" are one bug wearing two hats.

Two consequences worth knowing:

- **Mouse coordinates have to be converted.** The screen reports GUI units and the browser expects pixels,
  so every click, move and scroll is scaled on the way through. The ratio is taken from the two sizes the
  window reports rather than from `guiScale`, because the integer scale does not divide the window exactly
  and the rounding drifts by a pixel or two — enough to see over a 1920-pixel span.
- **It costs more to draw.** At scale 3 the browser paints nine times the pixels it used to. Chromium only
  repaints what changes, so an idle page is cheap and scrolling is not — see **Render quality** below.

### Render quality

Two different settings, easily confused:

| | |
|---|---|
| **Page zoom** | How big things *look*. Chromium's own zoom. |
| **Render quality** | How many pixels are *painted* to show them, then scaled back up to fill the window. |

Render quality is the lever for scroll cost. Chromium repaints the whole viewport while a page scrolls and
hands over a buffer to upload, so the bill is per pixel per frame: a full-resolution 1920×1080 browser is
about eight megabytes a frame, which at sixty frames a second is half a gigabyte a second across the bus.

It defaults to **80%** — a third fewer pixels — because full resolution was reported as heavy to scroll. The
page is slightly softer; the blit samples it back up with a linear filter rather than a blocky one. Set it
to 100% if you would rather have it exact.

One implementation note worth keeping: the render scale feeds the browser's **size and its input through the
same function**. They were briefly two expressions of the same ratio, which is exactly long enough to shrink
the texture without moving the cursor and put every click in the wrong place.

## Known: a segfault when the game exits

Opening the browser can make Minecraft crash **on shutdown**, in native code:

```
SIGSEGV  libharfbuzz.so.0
  hb_face_destroy ← hb_font_destroy
  ← FreeType.FT_Done_Face
  ← com.mojang.blaze3d.font.TrueTypeGlyphProvider.close()
  ← FontManager
```

**What is happening.** LWJGL ships its own FreeType with HarfBuzz linked *into* it. Chromium, on Linux,
brings the system GTK/Pango/fontconfig stack with it — including `/usr/lib/libharfbuzz.so.0`. Once both are
in the process, the system copy wins the global symbol lookup, so LWJGL's FreeType ends up calling a
different HarfBuzz than the one that built its font objects, and destroying one walks a mismatched struct.

**Why it waits until exit.** `hb_font_destroy` is only ever reached from `FT_Done_Face`, so its entry is
resolved *lazily, the first time a TrueType font is closed* — which is at shutdown. By then Chromium has
been loaded and the wrong copy is what the lookup finds. The same path runs on a resource reload, so `F3+T`
after opening the browser may do it too.

**Whose fault.** Nobody's exactly, and everybody's a bit: it needs LWJGL's statically linked HarfBuzz, a
system HarfBuzz, a TrueType font provider, and Chromium in one process. Sidequest supplies the TrueType
fonts and the reason Chromium is there, so it is ours to know about.

**What it costs.** Nothing but dignity, as far as observed: every mod finishes saving before the segfault —
the crash is the last thing in the log. It does write a core dump each time, which will eat disk.

**Avoiding it:**

- Do not open the profile viewer, and Chromium never loads. This is why **Start the browser in advance is
  off by default** — turning it on loads Chromium every session and would make an occasional crash a
  certain one.
- Untested but plausible: set `LD_BIND_NOW=1` in the instance's environment. Binding eagerly resolves
  LWJGL's HarfBuzz calls when *its* FreeType loads, before Chromium exists, so the lazy-resolution race
  never happens. Worth trying; it is a hypothesis, not a verified fix.

### Starting Chromium in advance

Off by default. On, Chromium starts coming up when you join a world, so the first lookup is instant instead
of a progress bar. It stays opt-in because the first start fetches a few hundred megabytes, and doing that
unasked because somebody installed a mod for the waypoints is not a decision to make for them.

**Page zoom** is a separate setting, defaulting to 100% — what a real browser shows, because the page is now
rendered at the monitor's own resolution. Turn it up on a 4K display or a television. It maps onto
Chromium's own zoom, which counts in multiplicative steps of 1.2 rather than in percent.

## It stays on SkyCrypt

Chromium will follow any link it is given, and a general-purpose web browser is a much larger thing to have
shipped than a profile viewer. So the screen watches the address, and a click that leaves `shiiyu.moe` is
pulled back with a note. Press `O` to open that link in your real browser instead.

**This is a leash, not a gate.** MCEF owns the `CefClient` and does not hand out a seat for a request
handler, so the check is a poll: an off-site page has already begun loading by the time it is noticed. That
is honest about what it defends against — a stray click on a footer link turning the viewer into a browser —
and it is not a security boundary. Do not treat it as one.

The rule itself lives in `SkyCryptUrls.isAllowed` and is tested against every way of writing a hostile
address that contains a friendly one:

```
https://sky.shiiyu.moe@evil.example/      ← userinfo; the host is evil.example
https://sky.shiiyu.moe.evil.example/      ← a subdomain of somebody else's domain
https://notshiiyu.moe/                    ← the suffix without the separating dot
https://evil.example/?x=sky.shiiyu.moe    ← the name only in the query
```

All four contain the string `sky.shiiyu.moe`. None of them is SkyCrypt, and the obvious implementation —
`url.contains(HOST)` — passes every one. It also *rejects* `cms.shiiyu.moe`, which is where the real page
gets its images. The check parses and compares hosts for both reasons.

Usernames are validated the other way round: `statsUrl` **refuses** anything that is not
`[A-Za-z0-9_]{1,16}` rather than escaping it. A Minecraft name has exactly one shape, so anything else is a
mistake to report, not a string to encode — and refusing never raises the question of whether the escaping
was right.

## Why not render the stats natively?

It was the first plan, and SkyCrypt's data API says no:

```
GET https://sky.shiiyu.moe/api/v2/profile/<name>   →  403  (Cloudflare, "Sorry, you have been blocked")
GET https://sky.shiiyu.moe/stats/<name>/<profile>  →  200
GET https://sky.shiiyu.moe/api/head/<hash>         →  200
```

Every data endpoint is blocked while the pages and the image endpoints are served, from the same address, at
the same moment — so this is a deliberate rule of theirs about programmatic consumers, not an outage and not
an IP reputation problem. Building on an endpoint its operator is actively refusing would be both fragile
and rude.

The official Hypixel API is the other source, and it needs a key plus a great deal of arithmetic to turn raw
profile JSON into the numbers SkyCrypt shows. A real browser showing their real page needs neither, and is
never out of date.

## For the next person to touch this

Four files, and the split between them is load-bearing:

- `platform-core` · `profile/SkyCryptUrls` — URL building and the allowlist. No Minecraft, fully tested.
- `platform/minecraft/EmbeddedBrowsers` — is it installed, how far along is startup. **Names no MCEF type.**
- `platform/minecraft/Mcef` — the only file that does.
- `ui/minecraft/screen/ProfileBrowserScreen` — the browser view. Also names MCEF types.

A class is verified when it is first loaded, and a signature naming an absent class fails that verification.
So an innocent `fun browser(): MCEFBrowser?` on `EmbeddedBrowsers` would turn "MCEF is not installed" from a
supported state into a `NoClassDefFoundError` *at the moment somebody asks whether it is installed*. Keeping
the check and the types in separate files is what stops that being written by accident.

The property is checked in bytecode rather than trusted:

```
javap -p -c EmbeddedBrowsers.class | grep dimaskama   # must be empty
unzip -l Sidequest-*.jar | grep -c dimaskama          # must be 0 — MCEF is never bundled
```

Not bundling it also keeps the licences apart: MCEF is LGPL, which is comfortable to link against as a
separate mod and much less so to nest inside a closed-source jar.

One multiversion trap, already paid for once: `minecraft.gui.setScreen` exists on 26.2 and not on 26.1.2,
which is the form MCEF's own example uses. `minecraft.setScreenAndShow` is the spelling both have — and it
will not take null, so closing to no screen at all goes through `super.onClose()`.
