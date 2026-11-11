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

    /** Shows one finished picture, or a fade between two. */
    const val BLEND = """#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 frag;
uniform sampler2D uA;
uniform sampler2D uB;
uniform float uMix;
void main() {
    vec2 uv = vec2(vUv.x, 1.0 - vUv.y);
    frag = mix(texture(uA, uv), texture(uB, uv), uMix);
}
"""

    /**
     * The real sky, placed by the graphics chip: each star's position is worked out here from its
     * right ascension and declination, the sidereal time and where the TV is, then projected through
     * the camera. Stars, the Milky Way and constellation lines share it.
     */
    const val SKY_VERT = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in float aMag;
layout(location = 2) in vec3 aCol;
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
out vec4 vCol;
out float vCore;
void main() {
    float h = uLst - aPos.x;
    float sd = sin(aPos.y), cd = cos(aPos.y), ch = cos(h);
    float alt = asin(clamp(uSinLat * sd + uCosLat * cd * ch, -1.0, 1.0));
    float az = atan(-cd * sin(h), sd * uCosLat - cd * uSinLat * ch);
    float l = mod(az - uViewAz + 3.14159265, 6.28318531) - 3.14159265;
    float den = 1.0 + uSinP * sin(alt) + uCosP * cos(alt) * cos(l);
    float k = 2.0 / max(den, 0.25);
    vec2 s = vec2(uCx + uR * k * cos(alt) * sin(l), uCy - uR * k * (uCosP * sin(alt) - uSinP * cos(alt) * cos(l)));
    gl_Position = vec4(s.x / 640.0 - 1.0, 1.0 - s.y / 360.0, 0.0, 1.0);
    bool gone = alt < 0.0 || den < 0.25;
    vCore = 0.0;
    if (uMode == 0) {
        // Dimmer near the horizon, where its light crosses more air, and twinkling more there too.
        float m = aMag + 0.25 * (1.0 / max(sin(max(alt, 0.035)), 0.05) - 1.0);
        float a = clamp((uLim + 0.6 - m) / 1.8, 0.0, 1.0);
        float id = float(gl_VertexID);
        float tw = 0.12 + 0.3 * (1.0 - sin(max(alt, 0.0)));
        a *= (1.0 - tw * (0.5 + 0.5 * sin(uT * (1.3 + mod(id, 7.0) * 0.37) * 3.0 + id * 2.399))) * uClear;
        // r is the star's radius in pixels; the point is its diameter, with room around it for a glow.
        float r = max(0.95, (6.0 - aMag) * 0.52);
        bool bright = aMag < 1.5;
        float room = bright ? 5.0 : 2.0;
        gl_PointSize = max(3.0, 2.0 * r * room) * uScale;
        vCore = r / (r * room);
        vCol = vec4(aCol * a, a);
        if (gone || a <= 0.003) { gl_PointSize = 0.0; gl_Position = vec4(2.0, 2.0, 2.0, 1.0); }
    } else if (uMode == 1) {
        float a = 0.032 * aMag * uMilky * uClear;
        gl_PointSize = 28.0 * uScale;
        vCol = vec4(vec3(0.74, 0.8, 1.0) * a, a);
        if (alt < -0.03 || den < 0.25 || a <= 0.001) { gl_PointSize = 0.0; gl_Position = vec4(2.0, 2.0, 2.0, 1.0); }
    } else {
        float a = gone ? 0.0 : 0.045 * uLines * uClear;
        vCol = vec4(vec3(0.59, 0.7, 1.0) * a, a);
    }
}
"""

    const val SKY_FRAG = """#version 300 es
precision mediump float;
in vec4 vCol;
in float vCore;
uniform highp int uMode;
out vec4 frag;
void main() {
    if (uMode == 2) { frag = vCol; return; }
    float d = length(gl_PointCoord * 2.0 - 1.0);
    if (d > 1.0) discard;
    float a;
    if (uMode == 1) a = (1.0 - d) * (1.0 - d);
    else a = smoothstep(vCore * 1.5, vCore * 0.6, d) + 0.6 * pow(1.0 - d, 2.0);
    frag = vCol * min(a, 1.0);
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
out vec4 frag;
void main() {
    // Fine rays that drift along the curtain; a soft lower edge, fading out as it rises.
    float r = 0.55 + 0.25 * sin(vRay * 1.7 + uT * 0.9) + 0.2 * sin(vRay * 4.3 - uT * 1.6);
    float prof = smoothstep(0.0, 0.15, vV) * pow(1.0 - vV, 1.4);
    vec3 col = mix(vec3(0.33, 1.0, 0.62), vec3(0.85, 0.32, 0.62), smoothstep(0.35, 0.95, vV));
    float a = clamp(vA * prof * r * 1.1, 0.0, 1.0);
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
uniform vec3 uSky[3];
uniform vec3 uLay[4];
uniform vec4 uHaze;
uniform float uTop[4];
uniform float uBot[4];
uniform vec3 uCore;
uniform vec3 uGlow;
uniform vec3 uCloudC;
uniform vec2 uSunP;
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
    return texture(uLand, vec2(((x + uPan) / W * 1280.0 + 0.5) / 1281.0, (float(i) + 0.5) / 4.0)).r;
}

/** Layer i's own colour at p, with lit windows where it has buildings. */
vec3 layerAt(int i, vec2 p, float base) {
    vec3 c = uLay[i];
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
    if (f.x < 0.3 || f.x > 0.68 || f.y < 0.3 || f.y > 0.78) return c;
    float h = fract(sin(dot(vec3(floor(q), id * 917.0), vec3(12.9898, 78.233, 37.719))) * 43758.5453);
    bool on = h < uLit;
    // Now and then a window goes dark or comes on, each in its own time: in every stretch of 45 to 135
    // seconds a window has a small chance of being the other way. A still keeps the moment it was drawn.
    float slot = floor(uT / (45.0 + 90.0 * fract(h * 5.0)) + fract(h * 11.0));
    if (fract(sin(dot(vec3(floor(q), slot + id * 31.0), vec3(39.3468, 11.135, 83.155))) * 43758.5453) < 0.08) on = !on;
    if (!on) return c;
    vec3 warm = fract(h * 13.0) > 0.9 ? vec3(0.76, 0.86, 1.0) : mix(vec3(1.0, 0.78, 0.5), vec3(1.0, 0.92, 0.74), fract(h * 7.0));
    return mix(c, warm, 0.6 + 0.35 * fract(h * 3.0));
}

vec3 orb(vec3 c, vec2 p, vec2 o, float r, vec3 core, vec3 glow, float a) {
    float d = distance(p, o);
    float g = clamp(1.0 - (d - r * 0.5) / (r * 8.5), 0.0, 1.0);
    c = mix(c, glow, 0.55 * a * g);
    return mix(c, core, a * (1.0 - smoothstep(r - uAa, r + uAa, d)));
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
    vec2 uv = p / vec2(W, H);
    if (uStarA > 0.01) {
        vec4 s = texture(uStars, vec2(uv.x, 1.0 - uv.y));
        c = c * (1.0 - s.a * uStarA) + s.rgb * uStarA;
    }
    if (uAuroraA > 0.01) c += texture(uAurora, uv).rgb * uAuroraA;
    if (uSunA > 0.01) c = orb(c, p, uSunP * vec2(W, H), uSunR, uCore, uGlow, uSunA);
    if (uMoon > 0.01) c = orb(c, p, vec2(0.8 * W, 0.18 * H), 16.0, vec3(0.933, 0.949, 1.0), vec3(0.784, 0.843, 1.0), uMoon);
    if (uBalloon.w > 0.003) c = balloon(c, p);
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
    c = mix(c, vec3(0.102, 0.141, 0.2), uDim);
    float d = distance(p, vec2(W, H) * 0.5);
    c *= 1.0 - 0.45 * clamp((d - H * 0.35) / (H * 0.6), 0.0, 1.0);
    c = weather(c, p);
    // The shade that keeps the text readable, painted in so it costs nothing on every frame.
    c *= 1.0 - 0.78 * clamp((vUv.y - 0.48) / 0.52, 0.0, 1.0);
    c *= 1.0 - 0.5 * clamp(1.0 - vUv.x / 0.6, 0.0, 1.0);
    frag = vec4(c * uLate, 1.0);
}
"""
}
