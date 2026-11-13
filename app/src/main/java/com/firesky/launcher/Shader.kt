package com.firesky.launcher

/** The landscape, drawn entirely on the graphics chip from the numbers in [Sky] and [Flow]. */
object Shader {
    const val VERT = """#version 300 es
in vec2 aPos;
out vec2 vUv;
void main() {
    vUv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    /**
     * Shows one finished picture, or a fade between two. While the home screen is in use the land
     * goes a little soft, as if the camera were focused on the cards in front of it: from where the
     * land begins ([uFocusY], in screen fractions from the top) downwards, by [uFocus]. The sky stays sharp.
     */
    const val BLEND = """#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 frag;
uniform sampler2D uA;
uniform sampler2D uB;
uniform float uMix;
uniform float uFocus;
uniform float uFocusY;
uniform vec2 uTexel;
vec4 soft(sampler2D t, vec2 uv, float k) {
    if (k <= 0.01) return texture(t, uv);
    // Four taps between texels, each a bilinear average of four: sixteen texels in all.
    vec2 o = uTexel * 1.5 * k;
    return 0.25 * (texture(t, uv + vec2(o.x, o.y * 0.4)) + texture(t, uv + vec2(-o.x * 0.4, o.y)) + texture(t, uv - vec2(o.x, o.y * 0.4)) + texture(t, uv - vec2(-o.x * 0.4, o.y)));
}
void main() {
    vec2 uv = vec2(vUv.x, 1.0 - vUv.y);
    float k = uFocus * smoothstep(uFocusY - 0.04, uFocusY + 0.1, vUv.y);
    // Only the picture that shows is read: a whole screen of extra reads costs the chip dearly.
    if (uMix <= 0.002) frag = soft(uA, uv, k);
    else if (uMix >= 0.998) frag = soft(uB, uv, k);
    else frag = mix(soft(uA, uv, k), soft(uB, uv, k), uMix);
}
"""

    /**
     * The real sky, placed by the graphics chip: each star's position is worked out here from its
     * right ascension and declination, the sidereal time and where the TV is, then projected through
     * the camera. Stars, the Milky Way, constellation lines and, in space, galaxies and nebulae share it.
     */
    const val SKY_VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in float aMag;
layout(location = 2) in vec3 aCol;
layout(location = 3) in vec4 aShape;
uniform highp int uMode;
uniform float uLst;
uniform float uSinLat;
uniform float uCosLat;
uniform float uViewAz;
uniform float uSinP;
uniform float uCosP;
uniform float uR;
uniform float uCx;
uniform float uCy;
uniform float uLim;
uniform float uScale;
uniform float uClear;
uniform float uMilky;
uniform float uLines;
uniform float uT;
uniform float uSpace;
out vec4 vCol;
out float vCore;
out vec4 vShape;

/** Where a point of the sky lands on screen (x, y), its altitude (z), and how far from the camera's back it is (w; under 0.25 is out of view). */
vec4 place(float ra, float dec) {
    float h = uLst - ra;
    float sd = sin(dec), cd = cos(dec), ch = cos(h);
    float alt = asin(clamp(uSinLat * sd + uCosLat * cd * ch, -1.0, 1.0));
    float az = atan(-cd * sin(h), sd * uCosLat - cd * uSinLat * ch);
    float l = mod(az - uViewAz + 3.14159265, 6.28318531) - 3.14159265;
    float den = 1.0 + uSinP * sin(alt) + uCosP * cos(alt) * cos(l);
    float k = 2.0 / max(den, 0.25);
    return vec4(uCx + uR * k * cos(alt) * sin(l), uCy - uR * k * (uCosP * sin(alt) - uSinP * cos(alt) * cos(l)), alt, den);
}

void hide() { gl_PointSize = 0.0; gl_Position = vec4(2.0, 2.0, 2.0, 1.0); }

void main() {
    vec4 q = place(aPos.x, aPos.y);
    vec2 s = q.xy;
    float alt = q.z;
    gl_Position = vec4(s.x / 640.0 - 1.0, 1.0 - s.y / 360.0, 0.0, 1.0);
    bool space = uSpace > 0.5;
    // Out in space no ground stands below the horizon to hide anything.
    bool gone = (alt < 0.0 && !space) || q.w < 0.25;
    vCore = 0.0;
    vShape = vec4(0.0);
    if (uMode == 0) {
        // Dimmer near the horizon, where its light crosses more air, and twinkling more there too. Space has no air.
        float m = aMag + (space ? 0.0 : 0.25 * (1.0 / max(sin(max(alt, 0.035)), 0.05) - 1.0));
        float a = clamp((uLim + 0.6 - m) / 1.8, 0.0, 1.0);
        // A garden shows the stars to about magnitude 5.3; space shows the fainter ones too.
        if (!space && aMag > 5.3) a = 0.0;
        float id = float(gl_VertexID);
        float tw = space ? 0.0 : 0.12 + 0.3 * (1.0 - sin(max(alt, 0.0)));
        a *= (1.0 - tw * (0.5 + 0.5 * sin(uT * (1.3 + mod(id, 7.0) * 0.37) * 3.0 + id * 2.399))) * uClear;
        // r is the star's radius in pixels; the point is its diameter, with room around it for a glow.
        float r = max(0.95, (6.0 - aMag) * 0.52);
        bool bright = aMag < 1.5;
        float room = bright ? 5.0 : 2.0;
        gl_PointSize = max(3.0, 2.0 * r * room) * uScale;
        vCore = r / (r * room);
        vCol = vec4(aCol * a, a);
        if (gone || a <= 0.003) hide();
    } else if (uMode == 1) {
        // The Milky Way, in space only: aMag is how bright this part of it is, one to five. A smooth glow,
        // wide and soft, as FireSky's skies are.
        float a = 0.03 * aMag * uMilky * uClear;
        gl_PointSize = 84.0 * uScale;
        vCol = vec4(aCol * a, a);
        if (q.w < 0.25 || a <= 0.001) hide();
    } else if (uMode == 2) {
        float a = gone ? 0.0 : (space ? 0.1 : 0.045 * uLines * uClear);
        vCol = vec4(vec3(0.59, 0.7, 1.0) * a, a);
    } else {
        // A galaxy, a cloud of gas or a cluster: an oval of light, its long axis lying the way the
        // real one's does on the sky, measured from north towards east. aMag is its size in degrees.
        vec4 n = place(aPos.x, aPos.y + 0.002);
        vec4 e = place(aPos.x + 0.002 / max(cos(aPos.y), 0.05), aPos.y);
        vec2 north = n.xy - s;
        vec2 east = e.xy - s;
        float pa = radians(aShape.y);
        vec2 major = normalize(normalize(north) * cos(pa) + normalize(east) * sin(pa));
        float size = clamp(aMag * 0.0174533 * length(north) / 0.002, 22.0, 260.0);
        gl_PointSize = size * uScale;
        vShape = vec4(major, aShape.x, aShape.z);
        vCol = vec4(aCol, aShape.w);
        if (gone || n.w < 0.25 || e.w < 0.25) hide();
    }
}
"""

    const val SKY_FRAG = """#version 300 es
precision mediump float;
in vec4 vCol;
in float vCore;
in vec4 vShape;
uniform highp int uMode;
out vec4 frag;

highp float hash(highp vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
/** Smooth noise, for the uneven light of gas clouds. */
float noise(highp vec2 p) {
    highp vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0)), f.x), f.y);
}
float clouds(highp vec2 q) { return 0.5 * noise(q * 2.5) + 0.3 * noise(q * 5.0 + 3.1) + 0.2 * noise(q * 10.0 + 7.7); }

void main() {
    if (uMode == 2) { frag = vCol; return; }
    if (uMode == 3) {
        // Across the long axis (u) and the short one (v), the oval made round.
        vec2 p = gl_PointCoord * 2.0 - 1.0;
        float ratio = max(vShape.z, 0.05);
        float u = dot(p, vShape.xy);
        float v = dot(p, vec2(-vShape.y, vShape.x)) / ratio;
        float r = length(vec2(u, v));
        if (r > 1.0) discard;
        float kind = vShape.w;
        vec3 col = vCol.rgb;
        float a;
        if (kind < 0.5) {
            // A spiral: a bright, warm core, and arms winding out through a bluer disc.
            float th = atan(v, u);
            float arms = 0.55 + 0.45 * cos(2.0 * th - 7.0 * log(r + 0.06));
            float disc = exp(-r * 3.2) * mix(1.0, arms, smoothstep(0.12, 0.4, r));
            float core = exp(-r * r * 90.0);
            // Seen nearly edge on, a lane of dust runs along it, a little off the middle.
            float lane = 1.0 - (1.0 - smoothstep(0.3, 0.45, ratio)) * 0.6 * exp(-pow((v * ratio - 0.03) / 0.028, 2.0)) * smoothstep(0.06, 0.25, abs(u));
            a = (disc * 0.9 + core) * lane;
            col = mix(col, vec3(1.0, 0.88, 0.7), clamp(core * 1.4 + exp(-r * 9.0) * 0.4, 0.0, 1.0));
        } else if (kind < 1.5) {
            // An elliptical galaxy: a smooth glow, warm and brightest in the middle.
            a = exp(-r * 5.0) + 0.6 * exp(-r * r * 60.0);
        } else if (kind < 2.5) {
            // Glowing gas: uneven, red with hydrogen, bluer where the young stars at its heart light it.
            // Each cloud's own unevenness, from which way it lies.
            float n = clouds(vec2(u, v * ratio) + vShape.xy * 9.0);
            a = exp(-r * r * 2.6) * smoothstep(0.25, 0.75, n) * 1.5 * (1.0 - smoothstep(0.7, 1.0, r));
            col = mix(col, vec3(0.62, 0.9, 1.0), exp(-r * r * 30.0) * 0.6);
        } else if (kind < 3.5) {
            // Starlight caught in dust: soft and blue, in wisps.
            a = exp(-r * r * 4.0) * (0.5 + 0.7 * clouds(p * 1.5 + 4.0));
        } else if (kind < 4.5) {
            // A globular cluster: a ball of stars too close together to tell apart, specks at its edge.
            vec2 g = floor(p * 18.0);
            float h = fract(sin(dot(g, vec2(12.9898, 78.233))) * 43758.5453);
            a = exp(-r * r * 9.0) * 0.8 + step(1.0 - 0.5 * exp(-r * r * 3.0), h) * 0.7 * (1.0 - r);
        } else {
            // An irregular galaxy: patches of stars and gas.
            float n = clouds(vec2(u, v * ratio) * 1.4 + vShape.xy * 5.0);
            a = exp(-r * r * 2.6) * (0.35 + n);
            col = mix(col, vec3(1.0, 0.6, 0.7), 0.25 * smoothstep(0.75, 0.9, n));
        }
        float k = clamp(a, 0.0, 1.0) * vCol.a;
        frag = vec4(col * k, k);
        return;
    }
    float d = length(gl_PointCoord * 2.0 - 1.0);
    if (d > 1.0) discard;
    float a;
    if (uMode == 1) { a = 1.0 - d * d; a *= a; }
    else a = smoothstep(vCore * 1.5, vCore * 0.6, d) + 0.6 * pow(1.0 - d, 2.0);
    frag = vCol * min(a, 1.0);
}
"""

    /** The constellations' names in space, each a strip of a picture of them all, placed by the app. */
    const val LABEL_VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec3 aUvA;
out vec3 vUvA;
void main() {
    gl_Position = vec4(aPos.x / 640.0 - 1.0, 1.0 - aPos.y / 360.0, 0.0, 1.0);
    vUvA = aUvA;
}
"""

    const val LABEL_FRAG = """#version 300 es
precision mediump float;
in vec3 vUvA;
uniform sampler2D uNames;
out vec4 frag;
void main() {
    float a = texture(uNames, vUvA.xy).a * vUvA.z;
    frag = vec4(vec3(0.72, 0.8, 1.0) * a, a);
}
"""

    /** The aurora's curtain, placed on screen by the app. */
    const val CURTAIN_VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in float aV;
layout(location = 2) in float aA;
layout(location = 3) in float aRay;
out float vV;
out float vA;
out float vRay;
void main() {
    gl_Position = vec4(aPos.x / 640.0 - 1.0, 1.0 - aPos.y / 360.0, 0.0, 1.0);
    vV = aV; vA = aA; vRay = aRay;
}
"""

    const val CURTAIN_FRAG = """#version 300 es
precision highp float;
in float vV;
in float vA;
in float vRay;
uniform float uT;
// Music's colours, low and high on the curtain, and how far it takes them.
uniform vec3 uLow;
uniform vec3 uHigh;
uniform float uTint;
out vec4 frag;
void main() {
    // Fine rays that drift along the curtain; a soft lower edge, fading out as it rises.
    float r = 0.55 + 0.25 * sin(vRay * 1.7 + uT * 0.9) + 0.2 * sin(vRay * 4.3 - uT * 1.6);
    float prof = smoothstep(0.0, 0.15, vV) * pow(1.0 - vV, 1.4);
    vec3 col = mix(vec3(0.33, 1.0, 0.62), vec3(0.85, 0.32, 0.62), smoothstep(0.35, 0.95, vV));
    float a = clamp(vA * prof * r * 1.1, 0.0, 1.0);
    col = mix(col, mix(uLow, uHigh, smoothstep(0.35, 0.95, vV)), uTint);
    // Light added to the sky, barely covering the stars behind it.
    frag = vec4(col * a, a * 0.25);
}
"""

    /** Things placed on screen by the app: planets, the moon in its phase, the space station, meteors. */
    const val DOT_VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in float aSize;
layout(location = 2) in vec4 aCol;
layout(location = 3) in float aKind;
uniform float uScale;
out vec4 vCol;
out float vKind;
void main() {
    gl_Position = vec4(aPos.x / 640.0 - 1.0, 1.0 - aPos.y / 360.0, 0.0, 1.0);
    gl_PointSize = (aKind > 0.5 ? aSize * 4.5 : aSize) * uScale;
    vCol = aCol;
    vKind = aKind;
}
"""

    const val DOT_FRAG = """#version 300 es
precision mediump float;
in vec4 vCol;
in float vKind;
uniform vec3 uMoonL;
out vec4 frag;
void main() {
    vec2 p = gl_PointCoord * 2.0 - 1.0;
    float d = length(p);
    if (d > 1.0) discard;
    if (vKind < 0.5) {
        float a = smoothstep(0.24, 0.12, d) + 0.6 * pow(1.0 - d, 2.5);
        frag = vec4(vCol.rgb, 1.0) * vCol.a * min(a, 1.0);
        return;
    }
    // The moon: a sphere lit from the sun's direction, so its phase and tilt are true. The unlit part
    // keeps a faint earthshine.
    float rr = 1.0 / 4.5;
    float lit = (uMoonL.z + 1.0) * 0.5;
    vec4 glow = vec4(0.86, 0.89, 1.0, 1.0) * 0.3 * lit * pow(max(0.0, 1.0 - d), 2.0);
    if (d > rr) { frag = glow * vCol.a; return; }
    vec2 q = p / rr;
    vec3 n = vec3(q, sqrt(max(0.0, 1.0 - dot(q, q))));
    float day = smoothstep(-0.04, 0.06, dot(n, uMoonL));
    vec3 col = mix(vec3(0.11, 0.12, 0.17), vCol.rgb * (0.82 + 0.18 * n.z), day);
    float edge = smoothstep(rr, rr - 0.02, d);
    frag = mix(glow, vec4(col, 1.0), edge) * vCol.a;
}
"""

    const val FRAG = """#version 300 es
precision highp float;
precision highp sampler2D;
in vec2 vUv;
out vec4 frag;
uniform sampler2D uRidge;
uniform sampler2D uStars;
uniform sampler2D uAurora;
uniform sampler2D uShim;
uniform sampler2D uCloud;
uniform sampler2D uFx;
// The Milky Way, drawn on its own at a quarter of the size: it's all soft glow, and that way costs a sixteenth.
uniform sampler2D uMilkyW;
uniform vec3 uSky[3];
uniform vec3 uLay[4];
uniform vec4 uHaze;
uniform float uTop[4];
uniform float uBot[4];
uniform vec3 uCore;
uniform vec3 uGlow;
uniform vec3 uCloudC;
uniform vec2 uSunP;
// How much of the sun is clear of the land in front of it, 0 to 1: a hidden sun makes no streak.
uniform float uSunVis;
uniform float uHz;
uniform float uWater;
uniform float uStarA;
uniform float uAuroraA;
uniform float uMoon;
uniform float uSunA;
uniform float uSunR;
uniform float uAa;
uniform float uT;
uniform float uDim;
uniform float uLate;
uniform int uKind;
uniform float uPan;
uniform float uLift;
uniform float uDrift;
uniform float uWave;
uniform float uFlash;
uniform vec3 uBolt;
uniform float uSlant;
uniform vec4 uBalloon;
// One of the balloon's companions, on the idle screen: which (Sightings' kinds, 0 for none), and where
// as x, y, size (its sign the way it's going) and how much it shows.
uniform int uSightK;
uniform vec4 uSight;
// A place other than the mountains: its heights (r), each building's number (g) and wall height (b).
uniform sampler2D uLand;
uniform int uPlace;
uniform float uBase[4];
uniform float uHazeH[4];
uniform float uWin[4];
uniform float uLit;
uniform float uWl;
uniform int uFront;
uniform float uRipple;
// The light the water catches: where the moon or a low sun is (x, y) and how strongly (z), and its colour.
uniform vec3 uLight;
uniform vec3 uLightC;
// The plains: how much the wind shows on the grass (0 elsewhere), and how far the trees lean with it,
// and which layer's trees: the plains' middle rise, or the forest's nearest rank.
uniform float uGrass;
uniform float uSway;
uniform int uSwayL;
// The desert: 1 there, 0 elsewhere. Which way the sand faces is in the land's second channel, so one
// side of each dune is lit and the other shaded. And how much sand the wind lifts off the crests.
uniform int uDesert;
uniform float uSand;
// Out among the stars, where the sky and the Milky Way take the app's colour as every place's sky does.
uniform int uSpace;
// For measuring only, from adb: pieces switched off. 1 the grain, 2 the sun's streak.
uniform int uOff;
// Music playing: the sky leans to its cover's two colours, high (rgb) and low ([uMusicB]), by a.
uniform vec4 uMusic;
uniform vec3 uMusicB;

const float W = 1280.0;
const float H = 720.0;

float ridgeAt(float x, float i) {
    return texture(uRidge, vec2(((x + uPan) / W * 256.0 + 0.5) / 257.0, (i + 0.5) / 4.0)).r;
}

/** Where layer i's ground is, and how far above it its haze begins. */
float baseOf(int i, float hz) { return uPlace > 0 ? hz + uBase[i] : hz - (3.0 - float(i)) * 30.0; }
float hazeOf(int i) { return uPlace > 0 ? uHazeH[i] : (i < 3 ? 200.0 - float(i) * 38.0 : 0.0); }

/** The column of the place's heights at x, sliding with the camera as the land does. */
int column(float x) {
    float u = mod((x + uPan) / W, 2.0);
    if (u > 1.0) u = 2.0 - u;
    return int(clamp(u * 1280.0 + 0.5, 0.0, 1280.0));
}

float heightAt(float x, int i) {
    if (uPlace == 0) return ridgeAt(x, float(i));
    // On the plains the trees lean with the wind and sway, each a little in its own time. The hill
    // they stand on moves too, by a pixel or two, which on a rise that gentle doesn't show. Every 4
    // seconds and every minute, so it comes round with the water's hour.
    if (i == uSwayL && uSway != 0.0) x += uSway * (0.45 + 0.55 * sin(uWave * 1.5707963 + x * 0.021)) * (0.6 + 0.4 * sin(uWave * 0.10471976 + x * 0.0043));
    return texture(uLand, vec2(((x + uPan) / W * 1280.0 + 0.5) / 1281.0, (float(i) + 0.5) / 4.0)).r;
}

/**
 * Wind over the fields: soft bands of light running across the grass the way the wind blows, faster
 * on the nearer fields. They ride the clouds' own drift, so they keep the wind's speed and direction
 * and come round with it seamlessly.
 */
vec3 grass(vec3 c, vec2 p, int i) {
    float x = p.x + uPan;
    float near = float(i - 1) / 2.0;
    float a = texture(uCloud, vec2(x / (620.0 + 520.0 * near) + uDrift * 0.033, (p.y - 250.0) / 260.0)).a;
    float b = texture(uCloud, vec2(x / 380.0 + uDrift * 0.051 + 0.41, (p.y - 230.0) / 200.0)).a;
    // The cloud texture is faint, soft blobs at a tenth or so: scaled up to whole gusts.
    float g = smoothstep(0.06, 0.4, a * 0.75 + b * 0.45);
    return c * (1.0 + 0.38 * uGrass * g);
}

/** The colours of the sand, far to near: paler with distance. */
const vec3 SAND[4] = vec3[4](vec3(0.86, 0.73, 0.55), vec3(0.85, 0.66, 0.42), vec3(0.79, 0.56, 0.32), vec3(0.66, 0.44, 0.24));

/**
 * The desert's colour at p in layer i: sand in its own colours, darkened with the hour as the land's
 * colour is, leaning a little to the app's. One side of each dune faces the sun and the other doesn't;
 * the line between them runs down from the crest at a slant, as it does across a real dune, and fades
 * into the body of the dune below.
 */
vec3 desert(vec3 c, vec2 p, int i, float base) {
    float surface = base - texelFetch(uLand, ivec2(column(p.x), i), 0).r;
    float depth = max(p.y - surface, 0.0);
    float g = texelFetch(uLand, ivec2(column(p.x + depth * 0.7), i), 0).g;
    float lit = uSunP.x > 0.5 ? g : 1.0 - g;
    // How bright the land is at this hour, from its own colour, so night sand is dark.
    float day = clamp(dot(c, vec3(0.3, 0.59, 0.11)) * 2.3, 0.05, 1.1);
    vec3 own = mix(SAND[i] * day, c, 0.22);
    return own * (1.0 + 0.5 * (lit - 0.5) * (1.0 - 0.8 * smoothstep(6.0, 70.0, depth)));
}

/**
 * Sand blown off a dune's crest by the wind: a thin, patchy veil just above the edge, streaming the way
 * the clouds drift. Only where the picture moves.
 */
vec3 spindrift(vec3 c, vec2 p, int i, float base) {
    float above = (base - heightAt(p.x, i)) - p.y;
    if (above <= 0.0 || above > 18.0) return c;
    // Streaming downwind, faster than the clouds: blown sand is close and quick. Read along one line of
    // the cloud picture's puffs, so the wisps run with the wind rather than hang down.
    // The cloud picture's puffs, stretched long across the screen and squeezed thin up and down, so
    // each is a flat wisp lying along the wind and sliding with it.
    float x = (p.x + uPan) / 1700.0 + uDrift * 0.06 + float(i) * 0.37;
    float gust = texture(uCloud, vec2(x, 0.3 + above / 45.0 + float(i) * 0.09)).a * 1.6 + 0.5 * texture(uCloud, vec2(x * 1.9 + 0.3, 0.45 + above / 30.0)).a;
    float k = pow(1.0 - above / 18.0, 1.6) * smoothstep(0.1, 0.3, gust) * uSand;
    // Dust in the air catches the light: pale, whichever dune it comes off.
    float day = clamp(dot(uLay[0], vec3(0.3, 0.59, 0.11)) * 2.3, 0.08, 1.1);
    return mix(c, SAND[0] * day * 1.1, clamp(k * 0.38, 0.0, 0.32));
}

/** Layer i's own colour at p, with lit windows where it has buildings. */
vec3 layerAt(int i, vec2 p, float base) {
    vec3 c = uLay[i];
    if (uGrass > 0.0 && i > 0) c = grass(c, p, i);
    if (uDesert > 0) c = desert(c, p, i, base);
    if (uPlace == 0 || uWin[i] < 0.5 || uLit <= 0.001 || p.y > base - 5.0) return c;
    int x = column(p.x);
    vec4 here = texelFetch(uLand, ivec2(x, i), 0);
    float id = here.g;
    // Below the walls' top, clear of any spire or mast, and of the walls themselves.
    if (id <= 0.0 || p.y < base - here.b + 6.0) return c;
    if (texelFetch(uLand, ivec2(max(x - 3, 0), i), 0).g != id || texelFetch(uLand, ivec2(min(x + 3, 1280), i), 0).g != id) return c;
    vec2 cell = uWin[i] > 1.5 ? vec2(8.0, 11.0) : vec2(5.0, 7.0);
    vec2 q = vec2(p.x + uPan, base - p.y) / cell;
    vec2 f = fract(q);
    // How far outside the window's pane, in pixels: lit panes spill a little light onto the wall.
    vec2 out2 = max(max(vec2(0.3, 0.3) - f, f - vec2(0.68, 0.78)), 0.0) * cell;
    float spill = dot(out2, out2);
    if (spill > 4.0) return c;
    float h = fract(sin(dot(vec3(floor(q), id * 917.0), vec3(12.9898, 78.233, 37.719))) * 43758.5453);
    bool on = h < uLit;
    // Now and then a window goes dark or comes on, each in its own time: in every stretch of 45 to 135
    // seconds a window has a small chance of being the other way. A still keeps the moment it was drawn.
    float slot = floor(uT / (45.0 + 90.0 * fract(h * 5.0)) + fract(h * 11.0));
    if (fract(sin(dot(vec3(floor(q), slot + id * 31.0), vec3(39.3468, 11.135, 83.155))) * 43758.5453) < 0.08) on = !on;
    if (!on) return c;
    vec3 warm = fract(h * 13.0) > 0.9 ? vec3(0.76, 0.86, 1.0) : mix(vec3(1.0, 0.78, 0.5), vec3(1.0, 0.92, 0.74), fract(h * 7.0));
    if (spill > 0.0) return mix(c, warm, 0.11 * exp(-spill / 1.5));
    return mix(c, warm, 0.6 + 0.35 * fract(h * 3.0));
}

vec3 orb(vec3 c, vec2 p, vec2 o, float r, vec3 core, vec3 glow, float a) {
    float d = distance(p, o);
    float g = clamp(1.0 - (d - r * 0.5) / (r * 8.5), 0.0, 1.0);
    c = mix(c, glow, 0.55 * a * g);
    // And a wider, fainter bloom, as a lens gives anything that bright.
    c += glow * 0.14 * a * exp(-d / (r * 5.0));
    return mix(c, core, a * (1.0 - smoothstep(r - uAa, r + uAa, d)));
}

/** The colour space takes from the app: its sky's own hue, made light. */
vec3 spaceHue() {
    vec3 h = uSky[2] / max(max(uSky[2].r, max(uSky[2].g, uSky[2].b)), 0.02);
    return mix(h, vec3(1.0), 0.35);
}

/** How far p is from the line from a to b. */
float seg(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / dot(ba, ba), 0.0, 1.0);
    return length(pa - ba * h);
}

/** Birds heading home at dusk: a loose V of dark specks, wings beating, the leader in front. */
vec3 birds(vec3 c, vec2 p) {
    vec2 o = uSight.xy;
    float s = abs(uSight.z) / 4.0;
    float dir = sign(uSight.z);
    if (abs(p.x - o.x - dir * 40.0 * s) > 60.0 * s || abs(p.y - o.y) > 30.0 * s) return c;
    float ink = 0.0;
    for (int i = 0; i < 7; i++) {
        float fi = float(i);
        float side = mod(fi, 2.0) * 2.0 - 1.0;
        float rank = floor((fi + 1.0) / 2.0);
        vec2 b = o + vec2(dir * rank * 12.0, side * rank * 6.0) * s + vec2(sin(uT * 0.7 + fi * 1.7), sin(uT * 0.9 + fi)) * 1.5 * s;
        float beat = sin(uT * 9.0 + fi * 1.3);
        vec2 tip = vec2(3.6, -1.6 * beat - 0.4) * s;
        vec2 q = p - b;
        float d = min(seg(q, vec2(0.0), tip), seg(q, vec2(0.0), vec2(-tip.x, tip.y)));
        ink = max(ink, smoothstep(0.95, 0.25, d));
    }
    return mix(c, uSky[0] * 0.3, ink * uSight.w);
}

/** A kite on its string, the flyer out of sight behind the land, its tail streaming downwind. */
vec3 kite(vec3 c, vec2 p, float hz) {
    vec2 o = uSight.xy;
    float s = abs(uSight.z) / 7.0;
    float dir = sign(uSight.z);
    // The string, faint, down to the ground somewhere upwind.
    float ds = seg(p, o + vec2(0.0, 2.0 * s), vec2(o.x + dir * 180.0 * s, hz + 60.0));
    c = mix(c, vec3(0.9, 0.9, 0.86), smoothstep(0.8, 0.0, ds) * 0.28 * uSight.w);
    vec2 q = p - o;
    if (abs(q.x) > 30.0 * s || q.y < -10.0 * s || q.y > 46.0 * s) return c;
    float tilt = 0.22 * sin(uT * 0.8);
    q = vec2(cos(tilt) * q.x - sin(tilt) * q.y, sin(tilt) * q.x + cos(tilt) * q.y);
    // The tail: a wavy line with a bow or two, trailing away from the wind.
    if (q.y > 8.0 * s) {
        float along = q.y - 8.0 * s;
        float x = -dir * along * 0.35 + 2.5 * s * sin(along * 0.28 - uT * 5.0);
        float bow = step(0.0, 1.0 - abs(mod(along, 11.0 * s) - 5.5 * s) / (1.4 * s));
        float d = abs(q.x - x);
        return mix(c, vec3(0.95, 0.3, 0.25), max(smoothstep(0.9, 0.2, d), bow * smoothstep(2.2 * s, 1.0 * s, d)) * uSight.w * (1.0 - along / (38.0 * s)));
    }
    // The diamond: a short top and a longer bottom, two colours either side of its spine.
    float d = q.y < -s ? abs(q.x) / (5.0 * s) + (-s - q.y) / (6.0 * s) : abs(q.x) / (5.0 * s) + (q.y + s) / (9.0 * s);
    if (d > 1.0) return c;
    vec3 col = q.x < 0.0 ? vec3(0.95, 0.3, 0.25) : vec3(1.0, 0.82, 0.25);
    col *= 0.85 + 0.15 * step(0.0, q.y + s);
    return mix(c, col, uSight.w * smoothstep(1.0, 0.85, d));
}

/** A small sailboat out on the water, its sail catching the light, a faint reflection under it. */
vec3 boat(vec3 c, vec2 p, float wl) {
    float s = abs(uSight.z) / 15.0;
    float dir = sign(uSight.z);
    vec2 o = vec2(uSight.x, wl + 14.0 * s);
    vec2 q = p - o;
    if (abs(q.x) > 14.0 * s || q.y < -20.0 * s || q.y > 16.0 * s) return c;
    // Toward the stern: it sails before the wind, so the stern is upwind.
    float u = q.x * dir;
    float ink = 0.0; vec3 col = vec3(0.0);
    // The hull, low on the water, narrowing to its keel.
    if (q.y > -2.0 * s && q.y < 2.0 * s && abs(q.x) < 8.0 * s - (q.y + 2.0 * s) * 1.2) { ink = 1.0; col = vec3(0.12, 0.12, 0.15); }
    // The sail: from the mast back along the boom.
    float sy = q.y + 2.5 * s;
    if (sy < 0.0 && sy > -15.0 * s && u > -0.6 * s && u < 7.0 * s * (sy + 15.0 * s) / (15.0 * s)) {
        ink = 1.0; col = mix(vec3(0.94, 0.93, 0.88), uSky[1], 0.25);
    }
    // Its reflection, broken up by the waves.
    vec2 r = vec2(q.x + sin(p.y * 0.6 + uWave * 3.0) * 1.2 * s, -q.y + 1.0 * s);
    float ry = r.y + 2.5 * s;
    if (ink == 0.0 && q.y > 2.0 * s && ry < 0.0 && ry > -15.0 * s && r.x * dir > -0.6 * s && r.x * dir < 7.0 * s * (ry + 15.0 * s) / (15.0 * s)) {
        ink = 0.22; col = vec3(0.9, 0.9, 0.86);
    }
    return mix(c, col, ink * uSight.w);
}

/**
 * A lit train along the far rise of the plains after dark: carriages with their windows lit, one
 * after another, following the lie of the land, the engine's lamp throwing light ahead.
 */
vec3 train(vec3 c, vec2 p, float base) {
    float len = abs(uSight.z);
    float dir = sign(uSight.z);
    float u = (p.x - uSight.x) * dir;
    if (u < -60.0 || u > len) return c;
    float y = base - heightAt(p.x, 1) - p.y;
    if (u < 0.0) {
        // The lamp's light along the track ahead.
        return c + vec3(1.0, 0.86, 0.6) * 0.18 * uSight.w * exp(u / 18.0) * exp(-abs(y - 3.0) / 2.0);
    }
    if (y < 0.5 || y > 6.0) return c;
    float car = mod(u, 22.0);
    if (car > 21.0) return c;
    vec3 col = uLay[1] * 0.5;
    if (u > 5.0 && y > 1.8 && y < 4.3 && fract(car / 3.0) > 0.4) col = vec3(1.0, 0.84, 0.55);
    if (u < 2.0 && y > 2.0 && y < 4.0) col = vec3(1.0, 0.95, 0.8);
    return mix(c, col, uSight.w);
}

/** A hot-air balloon, far off: striped, lit from the sun's side, with its basket hanging below. */
vec3 balloon(vec3 c, vec2 p) {
    vec2 q = (p - uBalloon.xy) / uBalloon.z;
    if (abs(q.x) > 1.3 || q.y < -1.3 || q.y > 2.0) return c;
    float aa = 1.2 / uBalloon.z;
    // Round above, narrowing to its mouth below.
    float w = q.y <= 0.0 ? sqrt(max(0.0, 1.0 - q.y * q.y)) : max(sqrt(max(0.0, 1.0 - q.y * q.y)), mix(1.0, 0.28, clamp(q.y / 1.3, 0.0, 1.0)));
    float body = (q.y < -1.0 || q.y > 1.3) ? 0.0 : smoothstep(-aa, aa, w - abs(q.x));
    float u = clamp(q.x / max(w, 0.05), -1.0, 1.0);
    float gore = mod(floor((asin(u) + 1.5708) / 0.449), 2.0);
    vec3 col = mix(vec3(0.84, 0.26, 0.2), vec3(0.97, 0.9, 0.76), gore);
    col = mix(col, vec3(0.98, 0.78, 0.3), smoothstep(0.08, 0.0, abs(q.y - 0.15)) * 0.8);
    float side = uSunP.x * W > uBalloon.x ? u : -u;
    col *= 0.5 + 0.5 * clamp(0.55 + 0.45 * side, 0.0, 1.0);
    float basket = smoothstep(aa, -aa, max(abs(q.x) - 0.13, abs(q.y - 1.6) - 0.1));
    col = mix(col, uSky[1] * 0.5, 0.12);
    c = mix(c, col, body * uBalloon.w);
    return mix(c, vec3(0.26, 0.18, 0.12), basket * uBalloon.w);
}

vec3 sky(vec2 p, float hz) {
    float t = clamp(p.y / hz, 0.0, 1.0);
    vec3 c = t < 0.55 ? mix(uSky[0], uSky[1], t / 0.55) : mix(uSky[1], uSky[2], (t - 0.55) / 0.45);
    if (uMusic.a > 0.001) {
        // The music's colours, kept to the sky's own brightness so the words stay readable, with a little
        // glow of their own so they show at night too.
        vec3 m = mix(uMusic.rgb, uMusicB, t);
        float l = dot(c, vec3(0.3, 0.59, 0.11));
        float ml = max(dot(m, vec3(0.3, 0.59, 0.11)), 0.05);
        c = mix(c, m * (l / ml) + m * 0.06, uMusic.a);
    }
    vec2 uv = p / vec2(W, H);
    if (uStarA > 0.01) {
        vec4 m = texture(uMilkyW, vec2(uv.x, 1.0 - uv.y));
        if (uSpace == 1) {
            // The Milky Way in the sky's own colour, a soft band rather than grey cloud.
            m.rgb = dot(m.rgb, vec3(0.3, 0.59, 0.11)) * spaceHue() * 1.15;
            m *= 0.8;
        }
        c = c * (1.0 - m.a * uStarA) + m.rgb * uStarA;
        vec4 s = texture(uStars, vec2(uv.x, 1.0 - uv.y));
        c = c * (1.0 - s.a * uStarA) + s.rgb * uStarA;
    }
    if (uAuroraA > 0.01) c += texture(uAurora, uv).rgb * uAuroraA;
    if (uSunA > 0.01) c = orb(c, p, uSunP * vec2(W, H), uSunR, uCore, uGlow, uSunA);
    if (uMoon > 0.01) c = orb(c, p, vec2(0.8 * W, 0.18 * H), 16.0, vec3(0.933, 0.949, 1.0), vec3(0.784, 0.843, 1.0), uMoon);
    if (uBalloon.w > 0.003) c = balloon(c, p);
    if (uSightK == 2 && uSight.w > 0.003) c = birds(c, p);
    if (uSightK == 3 && uSight.w > 0.003) c = kite(c, p, hz);
    if (uBolt.z > 0.003) {
        // A bolt of lightning, in the sky and so behind the mountains: a thin line from the cloud down,
        // wandering further the lower it gets. Its kinks are borrowed from the ridges, which are jagged already.
        float yy = p.y / hz;
        // Read back and forth along the ridge, never off one end and on at the other, so the line can't break.
        float j = texture(uRidge, vec2(abs(fract(yy * 0.155 + uBolt.y) * 2.0 - 1.0), 0.125)).r / 200.0
            + 0.35 * texture(uRidge, vec2(abs(fract(yy * 0.85 + uBolt.y * 3.1) * 2.0 - 1.0), 0.375)).r / 160.0;
        float d = abs(p.x - (uBolt.x + (j - 0.6) * 130.0 * (0.25 + yy)));
        c += vec3(0.88, 0.92, 1.0) * uBolt.z * (smoothstep(2.4, 0.5, d) + 0.3 * smoothstep(16.0, 0.0, d));
    }
    return c;
}

/** The sky and the land up to (not including) layer [upto]: water reflects only what stands behind it. */
vec3 scene(vec2 p, int upto) {
    float hz = H * uHz + uLift;
    // Below a layer's lowest point nothing behind it can show, so start from that layer.
    int first = -1;
    for (int i = 3; i >= 0; i--) {
        if (first < 0 && i < upto && p.y > uBot[i]) first = i;
    }
    // Over water the sky meets the waterline, not the mountains' horizon.
    vec3 c = first >= 0 ? layerAt(first, p, baseOf(first, hz)) : sky(p, uPlace > 0 && uWl < 0.0 ? hz + uWl : hz);
    for (int i = max(first, 0); i < upto; i++) {
        float base = baseOf(i, hz);
        // Above a layer's highest point it can't reach this pixel, so skip looking it up.
        if (i != first && p.y > uTop[i]) {
            float y = base - heightAt(p.x, i);
            float k = smoothstep(y - uAa, y + uAa, p.y);
            if (k > 0.0) c = mix(c, layerAt(i, p, base), k);
        }
        if (i == 1 && uSightK == 5 && uSight.w > 0.003) c = train(c, p, base);
        if (uDesert > 0 && uSand > 0.0 && i >= 1) c = spindrift(c, p, i, base);
        float hh = hazeOf(i);
        if (hh > 0.0) {
            float y0 = base - hh;
            c = mix(c, uHaze.rgb, uHaze.a * clamp((p.y - y0) / (H - y0), 0.0, 1.0));
        }
    }
    return c;
}

/** Layers standing in front of the water, like rocks on the near shore, drawn over it. */
vec3 front(vec3 c, vec2 p) {
    float hz = H * uHz + uLift;
    for (int i = uFront; i < 4; i++) {
        float base = baseOf(i, hz);
        if (p.y > uTop[i]) {
            float y = base - heightAt(p.x, i);
            float k = smoothstep(y - uAa, y + uAa, p.y);
            if (k > 0.0) c = mix(c, layerAt(i, p, base), k);
        }
    }
    return c;
}

vec3 weather(vec3 c, vec2 p) {
    if (uKind == 0) return c;
    if (uKind <= 3) {
        float a = texture(uCloud, vec2(p.x / 1800.0 + uDrift * 0.0012, p.y / 340.0)).a;
        float b = texture(uCloud, vec2(p.x / 1200.0 + uDrift * 0.002 + 0.37, (p.y + 40.0) / 300.0)).a;
        float cover = clamp(a + b * 0.7, 0.0, 1.0);
        c = mix(c, uCloudC, cover * (uKind == 1 ? 0.9 : 0.75));
        if (uFlash > 0.003) {
            // Lightning lights the cloud from inside: most where it struck and high up, least in the gaps.
            float near = exp(-abs(p.x - uBolt.x) / 420.0);
            float up = 1.0 - smoothstep(H * 0.3, H * 0.8, p.y);
            c += vec3(0.76, 0.83, 1.0) * uFlash * (0.07 + 0.5 * cover) * (0.3 + 0.7 * near) * (0.35 + 0.65 * up);
        }
    }
    // Rain and snow fall by the clock, which starts again every hour: each of their speeds fits a
    // whole number of times into that hour, so nothing jumps when it does.
    if (uKind == 2) {
        float s = p.x + p.y * uSlant;
        float a = texture(uFx, vec2(s / 512.0, p.y / 512.0 - uT * 1.7)).a;
        float b = texture(uFx, vec2(s / 340.0 + 0.31, p.y / 340.0 - uT * 2.1)).a;
        c = mix(c, vec3(0.804, 0.871, 0.961), clamp(a + b * 0.7, 0.0, 1.0) * 0.38);
    } else if (uKind == 3) {
        float a = texture(uFx, vec2(p.x / 512.0 + sin(uT * 0.69987703 + p.y * 0.012) * 0.02, p.y / 512.0 - uT * 0.16)).a;
        float b = texture(uFx, vec2(p.x / 300.0 + 0.5 + sin(uT * 0.90058989 + p.y * 0.02) * 0.03, p.y / 300.0 - uT * 0.22)).a;
        c = mix(c, vec3(1.0), clamp(a + b * 0.8, 0.0, 1.0) * 0.82);
    } else if (uKind == 4) {
        float a = texture(uCloud, vec2(p.x / 2600.0 + uDrift * 0.0009, (p.y - 300.0) / 420.0)).a;
        float band = smoothstep(280.0, 380.0, p.y) * (1.0 - smoothstep(620.0, 720.0, p.y));
        c = mix(c, vec3(0.886, 0.906, 0.933), a * 0.85 * band);
    }
    return c;
}

void main() {
    vec2 p = vUv * vec2(W, H);
    float hz = H * uHz + uLift;
    float wl = uPlace > 0 && uWl < 0.0 ? hz + uWl : hz;
    bool below = p.y > wl && uWater > 0.01;
    vec3 c;
    if (below) {
        float d = (p.y - wl) / max(H - wl, 1.0);
        if (uRipple > 0.0) {
            // A place's water. Waves sway the reflection sideways and break it into slices that slip up
            // and down, more the nearer they are, so the far shore, lights and the moon shimmer and
            // stretch as they do on real water. They keep their own time, which runs only while the picture
            // moves, so a still redrawn later shows them as they were, and comes round each hour: each
            // speed fits a whole number of times into it, every 3, 4 and 6 seconds, and 2 minutes.
            float sway = sin(p.y * 0.31 + uWave * 1.5707963) * (0.5 + 6.0 * d) + sin(p.y * 0.07 + 1.7 + uWave * 1.0471976) * 3.0 * d;
            float slip = sin(p.x * 0.045 + p.y * 0.9 + uWave * 2.0943951) * sin(p.y * 0.37 - uWave * 1.5707963) * (1.5 + 14.0 * d);
            vec3 r = scene(vec2(p.x + sway, 2.0 * wl - p.y + slip), uFront);
            // Darker and deeper nearer the viewer, where you look down into the water rather than across
            // it: the sky's own colour, so day water stays blue and bright and night water goes black.
            vec3 deep = mix(uSky[0], uSky[1], 0.5) * 0.42;
            c = mix(r * (0.94 - 0.22 * d), deep, 0.1 + 0.42 * d);
            // Glints only where something bright is mirrored, drifting with the waves.
            vec4 s = texture(uShim, vec2(vUv.x + uWave / 120.0, d));
            c += s.a * 3.0 * clamp(dot(r, vec3(0.3, 0.59, 0.11)) * 1.4, 0.0, 1.0);
            // The path of light under the moon or a low sun: a glow broken into glints, wider nearer you.
            if (uLight.z > 0.01 && uLight.y < wl) {
                float across = exp(-pow((p.x + sway * 2.0 - uLight.x) / (6.0 + 170.0 * d), 2.0));
                // Broken dashes on the crests of the waves, short far off and longer near, riding them along.
                float crest = sin(p.y * (1.6 - 0.9 * d) + sin(p.x * 0.06 + uWave * 2.0943951) * 2.2 - uWave * 1.5707963);
                float split = sin(p.x * (0.5 - 0.35 * d) + p.y * 0.8 + uWave * 1.0471976);
                float glint = smoothstep(0.72, 0.97, crest) * smoothstep(-0.1, 0.5, split);
                // Not every crest catches it, so the dashes don't line up in rows.
                glint *= step(0.4, fract(sin(dot(floor(vec2(p.x / (4.0 + 8.0 * d), p.y / 3.0)), vec2(12.9898, 78.233))) * 43758.5453));
                c += uLightC * uLight.z * across * (0.22 * (1.0 - 0.7 * d) + 1.1 * glint);
            }
            // The far edge catches the sky's light.
            c += uSky[2] * 0.2 * exp(-(p.y - wl) / 1.6);
        } else {
            // The mountains' lake, as it always was.
            c = scene(vec2(p.x, 2.0 * wl - p.y), uFront);
            if (uWater <= 0.99) c = mix(scene(p, 4), c, uWater);
            c = mix(c, vec3(0.016, 0.031, 0.063), 0.4 * uWater);
            vec4 s = texture(uShim, vec2(vUv.x, d));
            c = c * (1.0 - s.a * uWater) + s.rgb * uWater;
        }
        if (uFront < 4) c = front(c, p);
    } else c = scene(p, 4);
    if (uSightK == 4 && uSight.w > 0.003 && uWl < 0.0) c = boat(c, p, wl);
    if (uSunA > 0.01 && (uOff & 2) == 0) {
        // A low sun draws a faint line of light across the picture through it, as a lens does, fading
        // as it climbs. Only while it's still above the horizon.
        vec2 sp = uSunP * vec2(W, H);
        float low = smoothstep(0.0, 18.0, hz - sp.y) * (1.0 - smoothstep(60.0, 220.0, hz - sp.y));
        float streak = exp(-abs(p.y - sp.y) / 1.3) * exp(-abs(p.x - sp.x) / 380.0) + 0.25 * exp(-abs(p.y - sp.y) / 6.0) * exp(-abs(p.x - sp.x) / 160.0);
        c += uGlow * uSunA * uSunVis * low * streak * 0.32;
    }
    c = mix(c, vec3(0.102, 0.141, 0.2), uDim);
    float d = distance(p, vec2(W, H) * 0.5);
    c *= 1.0 - 0.45 * clamp((d - H * 0.35) / (H * 0.6), 0.0, 1.0);
    c = weather(c, p);
    // The shade that keeps the text readable, painted in so it costs nothing on every frame.
    c *= 1.0 - 0.78 * clamp((vUv.y - 0.48) / 0.52, 0.0, 1.0);
    c *= 1.0 - 0.5 * clamp(1.0 - vUv.x / 0.6, 0.0, 1.0);
    // A grain finer than one step of colour, so long gradients like dusk don't break into bands.
    float n = (uOff & 1) != 0 ? 0.0 : fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) + fract(sin(dot(gl_FragCoord.xy, vec2(39.3468, 11.135))) * 24634.6345) - 1.0;
    frag = vec4(c * uLate + n / 255.0, 1.0);
}
"""
}
