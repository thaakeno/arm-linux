#version 450

layout(location = 0) in vec3 inWorldPos;
layout(location = 1) in vec3 inWorldNormal;
layout(location = 2) in vec3 inCameraPos;
layout(location = 3) in vec2 inUv;
layout(location = 4) flat in int inMaterial;
layout(location = 0) out vec4 outColor;

const float PI = 3.14159265358979323846;

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
    for (int i = 0; i < 5; ++i) {
        v += valueNoise(p) * a;
        p = p * 2.03 + vec2(13.1, 7.7);
        a *= 0.5;
    }
    return v;
}

vec3 environmentColor(vec3 R, float roughness) {
    float t = clamp(R.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 ground = vec3(0.028, 0.032, 0.038);
    vec3 horizon = vec3(0.18, 0.205, 0.235);
    vec3 zenith = vec3(0.58, 0.64, 0.71);
    vec3 env = mix(ground, horizon, smoothstep(0.0, 0.42, t));
    env = mix(env, zenith, smoothstep(0.42, 1.0, t));

    vec3 boxA = normalize(vec3(-0.48, 0.64, 0.60));
    vec3 boxB = normalize(vec3( 0.72, 0.24,-0.46));
    vec3 boxC = normalize(vec3( 0.08, 0.88,-0.44));
    float sharpA = mix(44.0, 8.0, roughness);
    float sharpB = mix(58.0, 10.0, roughness);
    env += vec3(1.00, 0.94, 0.86) * 0.52 * pow(max(dot(R, boxA), 0.0), sharpA);
    env += vec3(0.56, 0.66, 0.78) * 0.20 * pow(max(dot(R, boxB), 0.0), sharpB);
    env += vec3(0.92, 0.96, 1.00) * 0.12 * pow(max(dot(R, boxC), 0.0), mix(70.0, 12.0, roughness));
    return env;
}

float distributionGGX(vec3 N, vec3 H, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float ndh = max(dot(N, H), 0.0);
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / max(PI * d * d, 0.0001);
}

float geometrySchlickGGX(float ndv, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return ndv / max(ndv * (1.0 - k) + k, 0.0001);
}

float geometrySmith(vec3 N, vec3 V, vec3 L, float roughness) {
    return geometrySchlickGGX(max(dot(N, V), 0.0), roughness) *
           geometrySchlickGGX(max(dot(N, L), 0.0), roughness);
}

vec3 fresnelSchlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(1.0 - cosTheta, 5.0);
}

void materialParams(
    int material,
    vec3 worldPos,
    vec2 uv,
    out vec3 albedo,
    out float metallic,
    out float roughness
) {
    if (material == 0) {
        float cloud = fbm(uv * 6.0 + worldPos.xz * 0.28);
        float brushed = 0.5 + 0.5 * sin((uv.y + worldPos.y * 0.11) * 920.0 + valueNoise(uv * 55.0) * 4.0);
        float fine = valueNoise(uv * 180.0 + worldPos.xy * 21.0);
        albedo = vec3(0.42, 0.45, 0.49) * mix(0.89, 1.10, cloud);
        albedo *= mix(0.965, 1.025, fine);
        albedo *= mix(0.985, 1.012, brushed);
        metallic = 0.48;
        roughness = clamp(0.27 + (fine - 0.5) * 0.12, 0.18, 0.42);
    } else if (material == 1) {
        vec2 p = worldPos.xz;
        float broad = fbm(p * 0.75);
        float fine = valueNoise(p * 26.0);
        float aggregate = step(0.94, valueNoise(floor(p * 18.0))) * 0.055;
        albedo = vec3(0.105, 0.112, 0.120) * mix(0.76, 1.22, broad);
        albedo *= mix(0.94, 1.06, fine) + aggregate;
        vec2 cell = abs(fract(p * 0.5 + 0.5) - 0.5);
        float seam = 1.0 - smoothstep(0.478, 0.497, max(cell.x, cell.y));
        albedo *= mix(0.83, 1.0, seam);
        metallic = 0.0;
        roughness = clamp(0.80 + (fine - 0.5) * 0.10, 0.68, 0.94);
    } else if (material == 2) {
        albedo = vec3(0.78, 0.80, 0.82);
        metallic = 0.96;
        roughness = 0.10;
    } else if (material == 3) {
        float glaze = valueNoise(uv * 96.0);
        albedo = vec3(0.78, 0.24, 0.16) * mix(0.96, 1.04, glaze);
        metallic = 0.0;
        roughness = clamp(0.24 + (glaze - 0.5) * 0.06, 0.18, 0.32);
    } else {
        float grain = valueNoise(uv * 120.0);
        albedo = vec3(0.08, 0.09, 0.105) * mix(0.90, 1.08, grain);
        metallic = 0.02;
        roughness = clamp(0.58 + (grain - 0.5) * 0.16, 0.45, 0.72);
    }
}

float softCircleShadow(vec2 p, vec2 center, vec2 radii, float core, float strength) {
    vec2 q = (p - center) / radii;
    float d = length(q);
    float inner = 1.0 - smoothstep(core, 1.0, d);
    float outer = 1.0 - smoothstep(0.82, 1.42, d);
    return clamp(inner * strength + outer * strength * 0.45, 0.0, 0.72);
}

void main() {
    vec3 N = normalize(inWorldNormal);
    vec3 V = normalize(inCameraPos - inWorldPos);
    if (dot(N, V) <= 0.0) discard;

    vec3 albedo;
    float metallic;
    float roughness;
    materialParams(inMaterial, inWorldPos, inUv, albedo, metallic, roughness);

    if (inMaterial == 0) {
        // Visually roll the face normals into a tiny chamfer so the cube catches
        // realistic highlights without increasing geometry count.
        vec3 absP = abs(inWorldPos);
        vec3 edge = smoothstep(vec3(0.90), vec3(1.00), absP);
        vec3 rolled = normalize(N + sign(inWorldPos) * edge * (vec3(1.0) - abs(N)) * 0.34);
        N = normalize(mix(N, rolled, 0.72));
    }

    if (inMaterial == 1) {
        vec2 p = inWorldPos.xz;
        float shadow = 0.0;
        shadow = max(shadow, softCircleShadow(p, vec2(0.13, 0.12), vec2(1.36, 1.20), 0.45, 0.30));
        shadow = max(shadow, softCircleShadow(p, vec2(-2.15,-0.28), vec2(1.12, 0.90), 0.42, 0.23));
        shadow = max(shadow, softCircleShadow(p, vec2(2.12, 0.18), vec2(1.02, 0.86), 0.42, 0.22));
        shadow = max(shadow, softCircleShadow(p, vec2(0.12,-2.30), vec2(0.90, 0.76), 0.42, 0.19));
        albedo *= 1.0 - shadow;
    }

    vec3 keyDir = normalize(vec3(0.44, 0.88, 0.48));
    vec3 fillDir = normalize(vec3(-0.72, 0.34, 0.42));
    vec3 rimDir = normalize(vec3(-0.18, 0.58, -0.80));
    vec3 lightDirs[3] = vec3[3](keyDir, fillDir, rimDir);
    vec3 lightColors[3] = vec3[3](
        vec3(1.00, 0.91, 0.80) * 3.00,
        vec3(0.42, 0.56, 0.76) * 0.85,
        vec3(0.70, 0.78, 0.92) * 0.55
    );

    vec3 F0 = mix(vec3(0.04), albedo, metallic);
    vec3 Lo = vec3(0.0);

    for (int i = 0; i < 3; ++i) {
        vec3 L = lightDirs[i];
        vec3 H = normalize(V + L);
        float ndl = max(dot(N, L), 0.0);
        float ndv = max(dot(N, V), 0.0);
        float D = distributionGGX(N, H, roughness);
        float G = geometrySmith(N, V, L, roughness);
        vec3 F = fresnelSchlick(max(dot(H, V), 0.0), F0);
        vec3 numerator = D * G * F;
        float denominator = max(4.0 * ndv * ndl, 0.001);
        vec3 specular = numerator / denominator;
        vec3 kS = F;
        vec3 kD = (vec3(1.0) - kS) * (1.0 - metallic);
        Lo += (kD * albedo / PI + specular) * lightColors[i] * ndl;
    }

    float ndv = max(dot(N, V), 0.0);
    vec3 R = reflect(-V, N);
    vec3 env = environmentColor(R, roughness);
    vec3 Fenv = fresnelSchlick(ndv, F0);
    vec3 diffuseEnv = albedo * (1.0 - metallic) * vec3(0.16, 0.18, 0.21);
    vec3 specEnv = env * Fenv * mix(1.0, 0.30, roughness);

    float horizonAO = mix(0.68, 1.0, smoothstep(-0.15, 0.55, N.y));
    float contactAO = 1.0;
    if (inMaterial != 1) {
        float height = max(inWorldPos.y + 1.01, 0.0);
        contactAO = mix(0.78, 1.0, smoothstep(0.0, 0.45, height));
    }

    vec3 linearColor = (diffuseEnv + specEnv + Lo) * horizonAO * contactAO;

    // ACES-like filmic compression, then display gamma. This keeps the softbox
    // highlights bright without blowing the whole material to white.
    linearColor = max(linearColor, vec3(0.0));
    linearColor = (linearColor * (2.51 * linearColor + 0.03)) /
                  (linearColor * (2.43 * linearColor + 0.59) + 0.14);
    vec3 displayColor = pow(clamp(linearColor, 0.0, 1.0), vec3(1.0 / 2.2));
    outColor = vec4(displayColor, 1.0);
}
