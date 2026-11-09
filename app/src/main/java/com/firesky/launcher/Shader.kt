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
uniform float uWind;

const float W = 1280.0;
const float H = 720.0;

float ridgeAt(float x, float i) {
    return texture(uRidge, vec2(((x + uPan) / W * 256.0 + 0.5) / 257.0, (i + 0.5) / 4.0)).r;
}

vec3 orb(vec3 c, vec2 p, vec2 o, float r, vec3 core, vec3 glow, float a) {
    float d = distance(p, o);
    float g = clamp(1.0 - (d - r * 0.5) / (r * 8.5), 0.0, 1.0);
    c = mix(c, glow, 0.55 * a * g);
    return mix(c, core, a * (1.0 - smoothstep(r - uAa, r + uAa, d)));
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
    return c;
}

vec3 scene(vec2 p) {
    float hz = H * uHz + uLift;
    // Below a ridge's lowest point nothing behind it can show, so start from that ridge.
    int first = -1;
    for (int i = 3; i >= 0; i--) {
        if (first < 0 && p.y > uBot[i]) first = i;
    }
    vec3 c = first >= 0 ? uLay[first] : sky(p, hz);
    for (int i = max(first, 0); i < 4; i++) {
        float fi = float(i);
        float base = hz - (3.0 - fi) * 30.0;
        // Above a ridge's highest point it can't reach this pixel, so skip looking it up.
        if (i != first && p.y > uTop[i]) {
            float y = base - ridgeAt(p.x, fi);
            c = mix(c, uLay[i], smoothstep(y - uAa, y + uAa, p.y));
        }
        if (i < 3) {
            float y0 = base - (200.0 - fi * 38.0);
            c = mix(c, uHaze.rgb, uHaze.a * clamp((p.y - y0) / (H - y0), 0.0, 1.0));
        }
    }
    return c;
}

vec3 weather(vec3 c, vec2 p) {
    if (uKind == 0) return c;
    if (uKind <= 3) {
        float a = texture(uCloud, vec2(p.x / 1800.0 + uT * 0.0012 * uWind, p.y / 340.0)).a;
        float b = texture(uCloud, vec2(p.x / 1200.0 + uT * 0.002 * uWind + 0.37, (p.y + 40.0) / 300.0)).a;
        c = mix(c, uCloudC, clamp(a + b * 0.7, 0.0, 1.0) * (uKind == 1 ? 0.9 : 0.75));
    }
    if (uKind == 2) {
        float s = p.x + p.y * 0.21;
        float a = texture(uFx, vec2(s / 512.0, p.y / 512.0 - uT * 1.7)).a;
        float b = texture(uFx, vec2(s / 340.0 + 0.31, p.y / 340.0 - uT * 2.1)).a;
        c = mix(c, vec3(0.804, 0.871, 0.961), clamp(a + b * 0.7, 0.0, 1.0) * 0.38);
    } else if (uKind == 3) {
        float a = texture(uFx, vec2(p.x / 512.0 + sin(uT * 0.7 + p.y * 0.012) * 0.02, p.y / 512.0 - uT * 0.16)).a;
        float b = texture(uFx, vec2(p.x / 300.0 + 0.5 + sin(uT * 0.9 + p.y * 0.02) * 0.03, p.y / 300.0 - uT * 0.22)).a;
        c = mix(c, vec3(1.0), clamp(a + b * 0.8, 0.0, 1.0) * 0.82);
    } else if (uKind == 4) {
        float a = texture(uCloud, vec2(p.x / 2600.0 + uT * 0.0009, (p.y - 300.0) / 420.0)).a;
        float band = smoothstep(280.0, 380.0, p.y) * (1.0 - smoothstep(620.0, 720.0, p.y));
        c = mix(c, vec3(0.886, 0.906, 0.933), a * 0.85 * band);
    }
    return c;
}

void main() {
    vec2 p = vUv * vec2(W, H);
    float hz = H * uHz + uLift;
    bool below = p.y > hz && uWater > 0.01;
    vec3 c;
    if (below && uWater > 0.99) c = scene(vec2(p.x, 2.0 * hz - p.y));
    else {
        c = scene(p);
        if (below) c = mix(c, scene(vec2(p.x, 2.0 * hz - p.y)), uWater);
    }
    if (below) {
        c = mix(c, vec3(0.016, 0.031, 0.063), 0.4 * uWater);
        vec4 s = texture(uShim, vec2(vUv.x, (p.y - hz) / (H - hz)));
        c = c * (1.0 - s.a * uWater) + s.rgb * uWater;
    }
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
