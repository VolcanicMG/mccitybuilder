# Contact sheet of the Ponder icons, 10x: python3 tools/ponder_icons_sheet.py out.png (after ponder_icons.py)
import json, zlib, struct, sys
PAL = {'o':0x202020,'w':0xFFFFFF,'l':0xC8C8C8,'s':0x8E8E8E,'S':0x5C5C5C,'b':0xB58A55,'B':0x7A5530,'y':0xFFE680,'a':0xFFB31A,'A':0xB07010,
       'c':0x40E6FF,'C':0x1E8FA8,'g':0x6CB44A,'G':0x3F7F2E,'d':0x8B5E3C,'D':0x5E3F27,'p':0xA88A5C,'P':0x8A6E44}
def png(path, w, h, px):
    raw = b''.join(b'\x00' + b''.join(struct.pack('>I', p)[1:] for p in px[y*w:(y+1)*w]) for y in range(h))
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    open(path, 'wb').write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))
d = json.load(open('build/ponder_icons.json'))
items = list(d['icons'].items()) + list(d['cursors'].items())
K, pad = 10, 4
cols = 6
cw = 16 * K + 2 * pad
rows = (len(items) + cols - 1) // cols
W, H = cols * cw, rows * cw
px = [0x3A4450] * (W * H)
for n, (name, g) in enumerate(items):
    ox, oy = (n % cols) * cw + pad, (n // cols) * cw + pad
    for y in range(16):
        for x in range(len(g[y])):
            ch = g[y][x]
            # checker background behind clear pixels
            c = PAL.get(ch, 0x4A5562 if (x + y) % 2 else 0x434D59)
            for j in range(K):
                for i in range(K): px[(oy + y * K + j) * W + ox + x * K + i] = c
png(sys.argv[1], W, H, px)
print(W, H, [n for n, _ in items])
