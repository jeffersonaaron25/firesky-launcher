#!/usr/bin/env python3
"""
Builds app/src/main/assets/sky.json from d3-celestial's data (https://github.com/ofrohn/d3-celestial,
BSD-3-Clause): stars to magnitude 6, the constellation figures already in sky.json, the names of the
better-known constellations, and the Milky Way from its outline catalogue (Jose R. Vieira), sampled
into soft points.

    python3 tools/make_sky.py <folder with stars.6.json, constellations.json and mw.json> [--avoid REGEX]

Pure Python, no packages needed.
"""
import json
import math
import os
import random
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'sky.json')

# Equatorial (J2000) to galactic: g = A e.
A = [[-0.0548755604, -0.8734370902, -0.4838350155],
     [0.4941094279, -0.4448296300, 0.7469822445],
     [-0.8676661490, -0.1980763734, 0.4559837762]]


# Numbers to round a hair further, given with --avoid: a privacy check that refuses anything looking
# like a particular place can catch a sky position by chance.
AVOID = None


def num(v):
    v = round(v, 2)
    return round(v, 1) if AVOID and AVOID.search(f'{v:.2f}') else v


def ra360(lon):
    return lon + 360 if lon < 0 else lon


def to_gal(ra, dec):
    a, d = math.radians(ra), math.radians(dec)
    e = (math.cos(d) * math.cos(a), math.cos(d) * math.sin(a), math.sin(d))
    g = [sum(A[i][k] * e[k] for k in range(3)) for i in range(3)]
    return math.degrees(math.atan2(g[1], g[0])) % 360, math.degrees(math.asin(max(-1, min(1, g[2]))))


class Level:
    """One brightness step of the Milky Way, as rings in galactic coordinates, tested with a ray up a meridian."""

    def __init__(self, rings):
        self.buckets = [[] for _ in range(360)]
        for ring in rings:
            pts = [to_gal(ra360(lon), lat) for lon, lat in ring]
            for (l1, b1), (l2, b2) in zip(pts, pts[1:] + pts[:1]):
                dl = (l2 - l1 + 180) % 360 - 180
                if dl == 0:
                    continue
                lo, hi = (l1, l1 + dl) if dl > 0 else (l1 + dl, l1)
                edge = (l1, b1, dl, b2 - b1, lo, hi)
                for k in range(math.floor(lo), math.floor(hi) + 1):
                    self.buckets[k % 360].append(edge)

    def inside(self, l, b):
        n = 0
        for (l1, b1, dl, db, lo, hi) in self.buckets[int(l) % 360]:
            for x in (l, l + 360, l - 360):
                if lo <= x < hi:
                    t = (x - l1) / dl
                    if b1 + db * t < b:
                        n += 1
                    break
        return n % 2 == 1


def milky(mw):
    levels = [Level([r for poly in f['geometry']['coordinates'] for r in poly]) for f in mw['features']]
    out = []
    # Points spread evenly over the sky, about 1.4 degrees apart, each nudged a little at random and a
    # little brighter or fainter, so the band looks mottled like clouds of stars rather than a lattice.
    n = 21000
    golden = math.pi * (3 - math.sqrt(5))
    rnd = random.Random(7)
    for i in range(n):
        z = 1 - 2 * (i + .5) / n
        ra = (math.degrees(golden * i) + rnd.uniform(-.7, .7) / max(math.sqrt(1 - z * z), .2)) % 360
        dec = max(-90, min(90, math.degrees(math.asin(z)) + rnd.uniform(-.7, .7)))
        l, b = to_gal(ra, dec)
        k = 0
        for lv in levels:
            if lv.inside(l, b):
                k += 1
            else:
                break
        if k == 0:
            continue
        # Warmer towards the centre of the galaxy, in Sagittarius.
        ll = (l + 180) % 360 - 180
        warm = math.exp(-(ll / 35) ** 2) * math.exp(-(b / 12) ** 2)
        out.append([num(ra), num(dec), round(k * rnd.uniform(.7, 1.3), 2), round(warm, 2)])
    return out


def main(src):
    old = json.load(open(OUT))
    stars = json.load(open(os.path.join(src, 'stars.6.json')))
    cons = json.load(open(os.path.join(src, 'constellations.json')))
    mw = json.load(open(os.path.join(src, 'mw.json')))

    s = []
    for f in stars['features']:
        p = f['properties']
        if p['mag'] > 6.0:
            continue
        lon, lat = f['geometry']['coordinates']
        bv = float(p['bv']) if p.get('bv') not in (None, '') else .6
        s.append([num(ra360(lon)), num(lat), round(p['mag'], 2), round(bv, 2)])
    s.sort(key=lambda r: r[2])

    names = []
    for f in cons['features']:
        p = f['properties']
        if p['rank'] not in ('1', '2'):
            continue
        lon, lat = f['geometry']['coordinates']
        names.append([num(ra360(lon)), num(lat), p['name'], int(p['rank'])])

    out = {
        'stars': s,
        'lines': old['lines'],
        'names': names,
        'milky': milky(mw),
        'credit': 'Star positions, constellation lines and names from d3-celestial, (c) 2015 Olaf Frohn, BSD licence; '
                  'the Milky Way from the Milky Way Outline Catalog by Jose R. Vieira, via d3-celestial',
    }
    with open(OUT, 'w') as fh:
        json.dump(out, fh, separators=(',', ':'), ensure_ascii=False)
    print(len(s), 'stars,', len(out['lines']), 'figures,', len(names), 'names,', len(out['milky']), 'Milky Way points')


if __name__ == '__main__':
    if '--avoid' in sys.argv:
        AVOID = re.compile(sys.argv[sys.argv.index('--avoid') + 1])
    main(sys.argv[1])
