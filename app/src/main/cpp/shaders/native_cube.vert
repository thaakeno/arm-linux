#version 450

layout(location = 0) out vec3 outColor;

layout(push_constant) uniform Push {
    float yaw;
    float pitch;
    float aspect;
    float cameraDistance;
} pc;

const vec3 POSITIONS[36] = vec3[36](
    vec3(-1,-1, 1), vec3( 1,-1, 1), vec3( 1, 1, 1),
    vec3(-1,-1, 1), vec3( 1, 1, 1), vec3(-1, 1, 1),
    vec3( 1,-1,-1), vec3(-1,-1,-1), vec3(-1, 1,-1),
    vec3( 1,-1,-1), vec3(-1, 1,-1), vec3( 1, 1,-1),
    vec3( 1,-1, 1), vec3( 1,-1,-1), vec3( 1, 1,-1),
    vec3( 1,-1, 1), vec3( 1, 1,-1), vec3( 1, 1, 1),
    vec3(-1,-1,-1), vec3(-1,-1, 1), vec3(-1, 1, 1),
    vec3(-1,-1,-1), vec3(-1, 1, 1), vec3(-1, 1,-1),
    vec3(-1, 1, 1), vec3( 1, 1, 1), vec3( 1, 1,-1),
    vec3(-1, 1, 1), vec3( 1, 1,-1), vec3(-1, 1,-1),
    vec3(-1,-1,-1), vec3( 1,-1,-1), vec3( 1,-1, 1),
    vec3(-1,-1,-1), vec3( 1,-1, 1), vec3(-1,-1, 1)
);

const vec3 COLORS[6] = vec3[6](
    vec3(0.18, 0.95, 0.72),
    vec3(0.20, 0.52, 1.00),
    vec3(0.92, 0.35, 0.40),
    vec3(0.64, 0.36, 1.00),
    vec3(1.00, 0.76, 0.22),
    vec3(0.20, 0.78, 0.92)
);

void main() {
    int index = gl_VertexIndex;
    vec3 p = POSITIONS[index];

    float cy = cos(pc.yaw);
    float sy = sin(pc.yaw);
    float cx = cos(pc.pitch);
    float sx = sin(pc.pitch);

    p = vec3(cy * p.x + sy * p.z, p.y, -sy * p.x + cy * p.z);
    p = vec3(p.x, cx * p.y - sx * p.z, sx * p.y + cx * p.z);

    float zoom = clamp(6.2 / max(pc.cameraDistance, 0.1), 0.55, 1.95);
    float scale = 0.48 * zoom;
    float safeAspect = max(pc.aspect, 0.01);

    // Procedural vertex generation removes the vertex-buffer/input path from the
    // diagnostic completely. z=0.5 keeps every triangle safely inside Vulkan's
    // 0..1 clip-depth range, so any valid draw must be visible.
    gl_Position = vec4(
        p.x * scale / safeAspect,
        -p.y * scale,
        0.5,
        1.0
    );
    outColor = COLORS[index / 6];
}
