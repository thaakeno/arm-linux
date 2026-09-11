#version 450
layout(location = 0) in vec3 inPos;
layout(location = 1) in vec3 inColor;
layout(location = 0) out vec3 outColor;

layout(push_constant) uniform Push {
    float yaw;
    float pitch;
    float aspect;
    float cameraDistance;
} pc;

void main() {
    float cy = cos(pc.yaw);
    float sy = sin(pc.yaw);
    float cx = cos(pc.pitch);
    float sx = sin(pc.pitch);

    vec3 p = inPos;
    p = vec3(cy * p.x + sy * p.z, p.y, -sy * p.x + cy * p.z);
    p = vec3(p.x, cx * p.y - sx * p.z, sx * p.y + cx * p.z);

    // Keep the diagnostic cube entirely inside Vulkan clip space. The previous
    // hand-written perspective transform could produce valid presents while all
    // geometry was clipped on some drivers. Orthographic clip-space placement
    // makes the draw path unambiguous while preserving rotation and zoom.
    float zoom = clamp(6.2 / max(pc.cameraDistance, 0.1), 0.55, 1.95);
    float scale = 0.42 * zoom;
    float safeAspect = max(pc.aspect, 0.01);

    gl_Position = vec4(
        p.x * scale / safeAspect,
        -p.y * scale,
        0.50 + p.z * 0.12,
        1.0
    );
    outColor = inColor;
}
