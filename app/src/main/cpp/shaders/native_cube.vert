#version 450

layout(location = 0) out vec3 outWorldPos;
layout(location = 1) out vec3 outWorldNormal;
layout(location = 2) out vec3 outBaseColor;
layout(location = 3) out vec3 outCameraPos;
layout(location = 4) out vec2 outFaceUv;

layout(push_constant) uniform Push {
    float yaw;
    float pitch;
    float aspect;
    float cameraDistance;
} pc;

vec2 quadCorner(int corner) {
    if (corner == 0) return vec2(-1.0, -1.0);
    if (corner == 1) return vec2( 1.0, -1.0);
    if (corner == 2) return vec2( 1.0,  1.0);
    if (corner == 3) return vec2(-1.0, -1.0);
    if (corner == 4) return vec2( 1.0,  1.0);
    return vec2(-1.0, 1.0);
}

vec3 facePosition(int face, vec2 q) {
    if (face == 0) return vec3( q.x,  q.y,  1.0);
    if (face == 1) return vec3(-q.x,  q.y, -1.0);
    if (face == 2) return vec3( 1.0,  q.y, -q.x);
    if (face == 3) return vec3(-1.0,  q.y,  q.x);
    if (face == 4) return vec3( q.x,  1.0, -q.y);
    return vec3(q.x, -1.0, q.y);
}

vec3 faceNormal(int face) {
    if (face == 0) return vec3( 0.0,  0.0,  1.0);
    if (face == 1) return vec3( 0.0,  0.0, -1.0);
    if (face == 2) return vec3( 1.0,  0.0,  0.0);
    if (face == 3) return vec3(-1.0,  0.0,  0.0);
    if (face == 4) return vec3( 0.0,  1.0,  0.0);
    return vec3(0.0, -1.0, 0.0);
}

vec3 faceColor(int face) {
    // Neutral studio-gray material. Tiny face variation keeps edges readable
    // without turning the benchmark into a neon/debug cube.
    if (face == 0) return vec3(0.49, 0.52, 0.57);
    if (face == 1) return vec3(0.45, 0.48, 0.53);
    if (face == 2) return vec3(0.53, 0.55, 0.59);
    if (face == 3) return vec3(0.47, 0.50, 0.55);
    if (face == 4) return vec3(0.57, 0.59, 0.62);
    return vec3(0.42, 0.45, 0.50);
}

void main() {
    int face = gl_VertexIndex / 6;
    int corner = gl_VertexIndex - face * 6;
    vec2 faceUv = quadCorner(corner);
    vec3 worldPos = facePosition(face, faceUv);
    vec3 worldNormal = faceNormal(face);

    float cp = cos(pc.pitch);
    vec3 cameraPos = pc.cameraDistance * vec3(
        cp * sin(pc.yaw),
        sin(pc.pitch),
        cp * cos(pc.yaw)
    );

    vec3 forward = normalize(-cameraPos);
    vec3 right = normalize(cross(forward, vec3(0.0, 1.0, 0.0)));
    vec3 up = normalize(cross(right, forward));

    vec3 rel = worldPos - cameraPos;
    float viewX = dot(rel, right);
    float viewY = dot(rel, up);
    float viewZ = dot(rel, forward);

    // Android Vulkan surfaces may expose the swapchain extent in the device's
    // native orientation while SurfaceFlinger applies a 90/270-degree
    // pre-transform for landscape. The old code used that portrait aspect
    // directly, which expanded X by ~2x and made a real cube look like a box.
    // The benchmark is landscape-only, so normalize to the visible landscape
    // aspect regardless of which orientation the swapchain reports.
    float safeAspect = max(pc.aspect, 0.01);
    if (safeAspect < 1.0) safeAspect = 1.0 / safeAspect;

    float fovY = radians(46.0);
    float f = 1.0 / tan(fovY * 0.5);
    float nearPlane = 0.10;
    float farPlane = 64.0;

    float clipX = viewX * f / safeAspect;
    float clipY = -viewY * f;
    float clipZ = (farPlane / (farPlane - nearPlane)) * viewZ
                - (farPlane * nearPlane / (farPlane - nearPlane));

    gl_Position = vec4(clipX, clipY, clipZ, viewZ);
    outWorldPos = worldPos;
    outWorldNormal = worldNormal;
    outBaseColor = faceColor(face);
    outCameraPos = cameraPos;
    outFaceUv = faceUv;
}
