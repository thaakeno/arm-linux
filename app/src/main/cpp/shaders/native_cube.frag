#version 450

layout(location = 0) in vec3 inWorldPos;
layout(location = 1) in vec3 inWorldNormal;
layout(location = 2) in vec3 inBaseColor;
layout(location = 3) in vec3 inCameraPos;
layout(location = 0) out vec4 outColor;

void main() {
    vec3 N = normalize(inWorldNormal);
    vec3 V = normalize(inCameraPos - inWorldPos);

    // The current diagnostic renderer has no depth attachment yet. Because the
    // cube is convex, rejecting back-facing fragments produces the same solid
    // visible hull without the see-through faces from the old shader.
    if (dot(N, V) <= 0.0) {
        discard;
    }

    // Soft Blender-like studio lighting: warm key, cool fill, ambient sky and
    // a restrained specular highlight. Kept deliberately cheap for the native
    // Surface latency benchmark.
    vec3 keyDir = normalize(vec3(0.55, 0.85, 0.65));
    vec3 fillDir = normalize(vec3(-0.70, 0.25, 0.55));
    vec3 keyColor = vec3(1.00, 0.93, 0.84);
    vec3 fillColor = vec3(0.38, 0.56, 0.90);

    float key = max(dot(N, keyDir), 0.0);
    float fill = max(dot(N, fillDir), 0.0);
    float sky = 0.5 + 0.5 * max(N.y, 0.0);

    vec3 H = normalize(keyDir + V);
    float spec = pow(max(dot(N, H), 0.0), 52.0);
    float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0);

    vec3 ambient = inBaseColor * (0.18 + 0.10 * sky);
    vec3 diffuse = inBaseColor * (keyColor * (0.72 * key) + fillColor * (0.24 * fill));
    vec3 specular = vec3(1.0, 0.97, 0.92) * (0.32 * spec);
    vec3 rimLight = vec3(0.28, 0.46, 0.72) * (0.12 * rim);

    vec3 linearColor = ambient + diffuse + specular + rimLight;
    vec3 displayColor = pow(clamp(linearColor, 0.0, 1.0), vec3(1.0 / 2.2));
    outColor = vec4(displayColor, 1.0);
}
