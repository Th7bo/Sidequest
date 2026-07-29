"""Emit the icon sprites from hand-written pixel maps.

Kept as source rather than binary blobs so the art is reviewable in a diff: '#' is
opaque, '+' is 60% alpha for softening a diagonal, ' ' is transparent. Icons are white
so they can be tinted to any theme colour at draw time.
"""
import struct, zlib, os

W = H = 16
OUT = "src/main/resources/assets/sidequest/textures/gui/icon"

ICONS = {
# A gear: outer teeth, ring, hollow centre.
"gear": [
"                ",
"      ####      ",
"   ## #### ##   ",
"   ############ ",
"   ############ ",
"  ###  ####  ###",
" ####  #  #  ###",
" ###  #    #  ##",
" ###  #    #  ##",
" ####  #  #  ###",
"  ###  ####  ###",
"   ############ ",
"   ############ ",
"   ## #### ##   ",
"      ####      ",
"                ",
],
# Sliders: three tracks with knobs at different positions.
"sliders": [
"                ",
"                ",
"  ############  ",
"                ",
"      ##        ",
"      ##        ",
"  ############  ",
"                ",
"           ##   ",
"           ##   ",
"  ############  ",
"                ",
"    ##          ",
"    ##          ",
"                ",
"                ",
],
# A bell with a clapper.
"bell": [
"                ",
"       ##       ",
"      ####      ",
"     ######     ",
"    ########    ",
"    ########    ",
"   ##########   ",
"   ##########   ",
"  ############  ",
"  ############  ",
" ############## ",
" ############## ",
"################",
"                ",
"      ####      ",
"       ##       ",
],
# A monitor: screen with a stand.
"monitor": [
"                ",
"  ############  ",
"  #          #  ",
"  #          #  ",
"  #          #  ",
"  #          #  ",
"  #          #  ",
"  #          #  ",
"  #          #  ",
"  ############  ",
"                ",
"      ####      ",
"      ####      ",
"   ##########   ",
"   ##########   ",
"                ",
],
# A wrench, laid diagonally.
"wrench": [
"                ",
"          ####  ",
"         ###### ",
"         ##  ## ",
"         ###### ",
"        ######  ",
"       ####     ",
"      ####      ",
"     ####       ",
"    ####        ",
"   ####         ",
"  ####          ",
" ####           ",
" ###            ",
"  #             ",
"                ",
],
# A palette: rounded blob with paint wells.
"palette": [
"                ",
"     ######     ",
"   ##########   ",
"  ####    ####  ",
" ###   ##   ### ",
" ##   ####   ## ",
"###   ####   ###",
"###    ##    ###",
"###          ###",
"###   ####   ###",
" ##  ######  ## ",
" ###  ####  ### ",
"  #####  #####  ",
"   ####  ####   ",
"     ######     ",
"                ",
],
# An eye, for visual settings.
"eye": [
"                ",
"                ",
"                ",
"    ########    ",
"  ############  ",
" ####      #### ",
"###   ####   ###",
"##   ######   ##",
"##   ######   ##",
"###   ####   ###",
" ####      #### ",
"  ############  ",
"    ########    ",
"                ",
"                ",
"                ",
],
# A magnifier, for search.
"search": [
"                ",
"    ######      ",
"   ########     ",
"  ###    ###    ",
"  ##      ##    ",
"  ##      ##    ",
"  ##      ##    ",
"  ###    ###    ",
"   ########     ",
"    ######      ",
"      #####     ",
"        #####   ",
"          ####  ",
"           #### ",
"            ### ",
"                ",
],
}

ALPHA = {"#": 255, "+": 153, " ": 0}


def png(rows):
    raw = b""
    for row in rows:
        raw += b"\x00"
        for x in range(W):
            a = ALPHA[row[x] if x < len(row) else " "]
            # White, so the renderer's tint decides the colour.
            raw += bytes((255, 255, 255, a))

    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


os.makedirs(OUT, exist_ok=True)
for name, rows in ICONS.items():
    assert len(rows) == H, f"{name} has {len(rows)} rows"
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(png(rows))
    print(f"wrote {name}.png")
