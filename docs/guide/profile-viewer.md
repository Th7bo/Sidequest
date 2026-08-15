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

## Keys

The page fills the window, so everything is a key rather than a toolbar:

| Key | |
|---|---|
| `Esc` | Close |
| `O` | Open this page in your real browser |
| `F5` | Reload |
| `Backspace` | Back |

`Esc` is handled before the page sees it, deliberately — a web page must not be able to swallow the only key
that gets you out of it.

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
