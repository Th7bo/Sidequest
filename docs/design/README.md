# Design reference and current state

## Reference images

| File | What it defines |
| --- | --- |
| `reference-config-screen.png` | The configuration screen design language |
| `reference-mining-xp-hud.png` | The HUD design language (Phase 4 target) |

These are the source of truth for the visual layer. Per `ui.plan` §3 they were **not**
hard-coded up front — the framework primitives and theme tokens came first, and the
reference is now what the tokens are tuned against.

## Current state

`captures/` holds screenshots taken from a real client by the Phase 3 client gametest
(`./gradlew :26.2:runClientGameTest`). They are regenerated on every run, so they always
reflect the code rather than a stale export.

## Gap analysis: current render vs `reference-config-screen.png`

### Closed

| Was missing | Now |
| --- | --- |
| **Section cards** | Each section is a rounded, bordered card with an icon block, title, subtitle and internal row dividers. Implemented as `CardSliceNode`, which draws one horizontal slice of the card per row — so a card can span many rows and **still be virtualized**. This is what per-corner `Corners` was added to the renderer for. |
| **Header bar** | `ScreenHeaderNode`: title, subtitle, `Save & Close` (primary) and a close button, laid out from the right edge inwards. |
| **Sidebar footer** | Search and `Reset All` are pinned to the bottom of the sidebar by a weighted spacer — no coordinates computed anywhere. |
| **Panel inset** | The whole screen is inset from the window edge with rounded corners and a border, so it reads as a floating surface over the game rather than a takeover. |
| **Colour picker** | `ColorControlNode` draws a swatch plus its hex value, with a checker behind translucent colours. Activating it opens a swatch grid in the overlay layer. |
| **Popups** | `OverlayRootNode` hosts content above everything, so a dropdown list opened from a row inside a clipping, scrolling list escapes that clip and paints on top. Anchored placement flips and clamps to stay on screen; outside click and Escape dismiss. |
| **Icons** | `IconRegistry` resolves ids to a sprite or a procedural painter, scope-owned so a pack or a module can substitute art. Sprites live in `assets/sidequest/textures/gui/icon/`, generated from the pixel maps in `tools/generate-icons.py` so the art is reviewable in a diff. Icons are white and tinted at draw time. |
| **Right-hand panel** | `InfoPanelNode` carries About, Tip and Profile cards. It is **responsive**: present in the tree always, hidden when the settings list would drop below 300 logical units, because three unreadable columns are worse than two readable ones. Visible at GUI scale 1, hidden at scale 2 — both captured. |
| **Card subtitles** | Sections carry descriptions, shown under the card title. |

### Still open

| Gap | Notes |
| --- | --- |
| **Profile actions** | The Profile card shows the active profile; the create / rename / duplicate / delete buttons are not wired to `ProfileManager` yet. |
| **Links row** | The reference has Discord / GitHub / web buttons under the panel. |
| **Continuous colour picker** | The popup offers presets, which is the part that works without a mouse. A hue/saturation area can be added on the same overlay when there is a reason to. |
| **Popup keyboard traversal** | Tab reaches popup items because they are focusable, but the list does not trap focus or open with the selected item focused. |
| **Version / update status** | The reference shows `v1.0.0 • Up to date` under the sidebar footer. |

None of these are framework limitations: they are compositions of existing nodes.

## A note on scale

The reference was drawn at roughly 1536 logical units wide. Minecraft works in far less:
at 1280×720 with GUI scale 2 the logical viewport is 640×360, and at scale 3 it is
427×240. The design language is reproduced at Minecraft's scale rather than copied
pixel-for-pixel, which is why paddings and control widths are smaller than the reference
image suggests.

The client gametest pins GUI scale 2 so the captures are deterministic, and separately
captures scales 1 and 3 to show the layout adapting.
