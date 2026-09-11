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

void main() {
    vec3 N = normalize(inWorldNormal);
    vec3 V = normalize(inCameraPos - inWorldPos);

    if (dot(N, V) <= 0.0) {
        discard;
    }

    // Neutral studio rig: soft warm key from above/front, restrained cool fill,
    // and a broad skylight. The values intentionally avoid a glossy/neon look.
    vec3 keyDir = normalize(vec3(0.55, 0.88, 0.62));
    vec3 fillDir = normalize(vec3(-0.72, 0.30, 0.42));
    vec3 keyColor = vec3(1.00, 0.95, 0.89);
    vec3 fillColor = vec3(0.48, 0.57, 0.69);

    float key = max(dot(N, keyDir), 0.0);
    float fill = max(dot(N, fillDir), 0.0);
    float sky = 0.45 + 0.55 * max(N.y, 0.0);

    vec3 H = normalize(keyDir + V);
    float specPower = inMaterial == 0 ? 46.0 : 18.0;
    float specStrength = inMaterial == 0 ? 0.22 : 0.035;
    float spec = pow(max(dot(N, H), 0.0), specPower);

    vec3 base = inBaseColor;

    if (inMaterial == 0) {
        // Very subtle procedural surface grain so the faces don't look like
        // perfectly flat debug colors. No external texture assets required.
        float grain = hash21(inFaceUv * 180.0 + inWorldPos.xz * 23.0) - 0.5;
        base *= 1.0 + grain * 0.035;

        // Fake a tiny bevel visually near each face edge. It gives the cube a
        // more manufactured/Blender-like read without changing the mesh.
        float edgeDist = 1.0 - max(abs(inFaceUv.x), abs(inFaceUv.y));
        float edge = 1.0 - smoothstep(0.0, 0.055, edgeDist);
        base *= 1.0 - edge * 0.12;
        specStrength += edge * 0.08;
    } else {
        // Procedural matte floor with a low-contrast fine texture.
        float floorGrain = hash21(inWorldPos.xz * 55.0) - 0.5;
        base *= 1.0 + floorGrain * 0.045;

        // Soft contact shadow under the cube, offset slightly away from the key
        // light. It is deliberately subtle and feathered rather than a hard
        // game-style shadow map.
        vec2 shadowP = inWorldPos.xz + vec2(0.22, 0.18);
        float d2 = dot(shadowP * vec2(0.82, 1.05), shadowP * vec2(0.82, 1.05));
        float contact = 1.0 - smoothstep(0.65, 2.65, d2);
        float core = 1.0 - smoothstep(0.05, 0.95, d2);
        float shadow = clamp(contact * 0.24 + core * 0.08, 0.0, 0.30);
        base *= 1.0 - shadow;
    }

    vec3 ambient = base * (0.24 + 0.12 * sky);
    vec3 diffuse = base * (keyColor * (0.70 * key) + fillColor * (0.22 * fill));
    vec3 specular = vec3(1.0, 0.97, 0.92) * (specStrength * spec);

    float rim = pow(1.0 - max(dot(N, V), 0.0), 3.4);
    vec3 rimLight = vec3(0.36, 0.43, 0.52) * (inMaterial == 0 ? 0.07 * rim : 0.0);

    vec3 linearColor = ambient + diffuse + specular + rimLight;

    // Mild filmic-ish compression before display gamma keeps highlights from
    // blowing out and gives the neutral material a little more depth.
    linearColor = linearColor / (linearColor + vec3(0.32));
    vec3 displayColor = pow(clamp(linearColor, 0.0, 1.0), vec3(1.0 / 2.2));
    outColor = vec4(displayColor, 1.0);
}
