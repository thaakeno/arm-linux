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
    p.z += pc.cameraDistance;

    const float nearZ = 0.1;
    const float farZ = 100.0;
    const float focal = 1.7320508; // 60 degree vertical field of view
    gl_Position = vec4(
        p.x * focal / max(pc.aspect, 0.01),
        -p.y * focal,
        (farZ / (farZ - nearZ)) * p.z - (farZ * nearZ / (farZ - nearZ)),
        p.z
    );
    outColor = inColor;
}
