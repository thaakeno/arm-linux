#version 450

layout(location = 0) in vec3 inWorldPos;
layout(location = 1) in vec3 inWorldNormal;
layout(location = 2) in vec3 inBaseColor;
layout(location = 3) in vec3 inCameraPos;
layout(location = 4) in vec2 inFaceUv;
layout(location = 5) flat in int inMaterial;
layout(location = 0) out vec4 outColor;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; ++i) {
        v += valueNoise(p) * a;
        p = p * 2.03 + vec2(13.1, 7.7);
        a *= 0.5;
    }
    return v;
}

vec3 studioEnvironment(vec3 R) {
    float t = clamp(R.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 ground = vec3(0.045, 0.050, 0.055);
    vec3 sky = vec3(0.48, 0.53, 0.58);
    vec3 env = mix(ground, sky, smoothstep(0.10, 0.92, t));

    // Two broad virtual soft boxes. They create recognizable reflections on
    // the material without turning the benchmark into a neon demo.
    vec3 softA = normalize(vec3(-0.52, 0.62, 0.58));
    vec3 softB = normalize(vec3( 0.70, 0.30,-0.48));
    env += vec3(1.00, 0.94, 0.86) * 0.34 * pow(max(dot(R, softA), 0.0), 18.0);
    env += vec3(0.58, 0.66, 0.76) * 0.12 * pow(max(dot(R, softB), 0.0), 24.0);
    return env;
}

void main() {
    vec3 N = normalize(inWorldNormal);
    vec3 V = normalize(inCameraPos - inWorldPos);

    if (dot(N, V) <= 0.0) {
        discard;
    }

    vec3 base = inBaseColor;
    float roughness = inMaterial == 0 ? 0.36 : 0.72;

    if (inMaterial == 0) {
        // Coherent layered microtexture: cloudy variation, fine grain and tiny
        // dark mineral flecks. This reads as a real manufactured surface rather
        // than six flat debug-color faces, while staying deliberately neutral.
        vec2 p = inFaceUv * 2.5 + inWorldPos.xy * 0.35 + inWorldPos.xz * 0.21;
        float cloud = fbm(p * 2.2);
        float fine = valueNoise(p * 42.0);
        float fleck = step(0.972, hash21(floor(p * 115.0)));
        base *= mix(0.88, 1.10, cloud);
        base *= mix(0.965, 1.035, fine);
        base *= 1.0 - fleck * 0.22;
        roughness += (fine - 0.5) * 0.08;

        // Pseudo-bevel normal. The mesh stays a mathematically perfect cube,
        // but normals roll near the edges so highlights behave like a tiny
        // chamfer instead of razor-sharp debug geometry.
        vec3 axisMask = vec3(1.0) - abs(N);
        vec3 edgeWeight = smoothstep(vec3(0.82), vec3(0.995), abs(inWorldPos)) * axisMask;
        vec3 edgeDir = sign(inWorldPos) * edgeWeight;
        N = normalize(N + edgeDir * 0.42);

        float edgeDistance = 1.0 - max(abs(inFaceUv.x), abs(inFaceUv.y));
        float bevelBand = 1.0 - smoothstep(0.0, 0.10, edgeDistance);
        roughness = mix(roughness, 0.22, bevelBand * 0.75);
        base *= 1.0 + bevelBand * 0.045;
    } else {
        // Dark concrete-like studio floor with broad mottling, fine aggregate
        // and very subtle grid seams for scale.
        vec2 p = inWorldPos.xz;
        float broad = fbm(p * 0.72);
        float fine = valueNoise(p * 22.0);
        base *= mix(0.78, 1.18, broad);
        base *= mix(0.95, 1.05, fine);

        vec2 cell = abs(fract(p * 0.5 + 0.5) - 0.5);
        float seam = 1.0 - smoothstep(0.475, 0.495, max(cell.x, cell.y));
        base *= mix(0.88, 1.0, seam);

        // Soft analytic contact shadow. A second, larger lobe fakes bounced
        // occlusion and anchors the cube to the floor without a shadow map.
        vec2 s = p + vec2(0.18, 0.14);
        float d = length(s * vec2(0.82, 1.08));
        float contact = 1.0 - smoothstep(0.35, 1.85, d);
        float penumbra = 1.0 - smoothstep(0.55, 3.05, d);
        float shadow = clamp(contact * 0.31 + penumbra * 0.11, 0.0, 0.38);
        base *= 1.0 - shadow;
    }

    vec3 keyDir = normalize(vec3(0.46, 0.86, 0.54));
    vec3 fillDir = normalize(vec3(-0.72, 0.36, 0.43));
    vec3 rimDir = normalize(vec3(-0.25, 0.55, -0.80));

    float key = max(dot(N, keyDir), 0.0);
    float fill = max(dot(N, fillDir), 0.0);
    float back = max(dot(N, rimDir), 0.0);

    vec3 H = normalize(keyDir + V);
    float gloss = mix(78.0, 15.0, clamp(roughness, 0.0, 1.0));
    float spec = pow(max(dot(N, H), 0.0), gloss);

    float ndv = max(dot(N, V), 0.0);
    float fresnel = pow(1.0 - ndv, 5.0);
    vec3 R = reflect(-V, N);
    vec3 env = studioEnvironment(R);

    vec3 ambient = base * (inMaterial == 0 ? 0.18 : 0.25);
    vec3 diffuse = base * (
        vec3(1.00, 0.94, 0.87) * (0.76 * key) +
        vec3(0.48, 0.57, 0.67) * (0.24 * fill) +
        vec3(0.36, 0.40, 0.46) * (0.09 * back));

    float specStrength = inMaterial == 0 ? mix(0.24, 0.08, roughness) : 0.025;
    vec3 specular = vec3(1.00, 0.97, 0.92) * spec * specStrength;
    vec3 reflection = inMaterial == 0 ? env * (0.055 + 0.16 * fresnel) * (1.0 - roughness * 0.55) : vec3(0.0);

    // Cheap corner AO on the cube gives the faces more depth, especially when
    // the key light is broad and soft.
    if (inMaterial == 0) {
        float corner = smoothstep(1.34, 1.66,
            abs(inWorldPos.x) + abs(inWorldPos.y) + abs(inWorldPos.z));
        ambient *= 1.0 - corner * 0.12;
    }

    vec3 linearColor = ambient + diffuse + specular + reflection;

    // Filmic compression and display gamma.
    linearColor = linearColor * (2.20 * linearColor + 0.08) /
                  (linearColor * (2.05 * linearColor + 0.72) + 0.18);
    vec3 displayColor = pow(clamp(linearColor, 0.0, 1.0), vec3(1.0 / 2.2));
    outColor = vec4(displayColor, 1.0);
}
