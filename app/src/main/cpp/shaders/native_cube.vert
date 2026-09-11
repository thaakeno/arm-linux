#version 450

layout(location = 0) out vec3 outWorldPos;
layout(location = 1) out vec3 outWorldNormal;
layout(location = 2) out vec3 outBaseColor;
layout(location = 3) out vec3 outCameraPos;

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
    // Blender-like neutral material with a very small per-face tint so the
    // silhouette stays readable even before the lighting contribution.
    if (face == 0) return vec3(0.48, 0.54, 0.63);
    if (face == 1) return vec3(0.43, 0.49, 0.58);
    if (face == 2) return vec3(0.54, 0.58, 0.66);
    if (face == 3) return vec3(0.46, 0.51, 0.60);
    if (face == 4) return vec3(0.58, 0.61, 0.68);
    return vec3(0.40, 0.46, 0.55);
}

void main() {
    int face = gl_VertexIndex / 6;
    int corner = gl_VertexIndex - face * 6;
    vec3 worldPos = facePosition(face, quadCorner(corner));
    vec3 worldNormal = faceNormal(face);

    // Orbit camera: horizontal drag changes yaw around world-up, vertical drag
    // changes pitch around the camera's local right axis. This feels much more
    // like Blender's orbit control than rotating the object in screen space.
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

    float safeAspect = max(pc.aspect, 0.01);
    float fovY = radians(43.0);
    float f = 1.0 / tan(fovY * 0.5);
    float nearPlane = 0.10;
    float farPlane = 64.0;

    // Vulkan NDC uses z in [0, 1]. Keep +Y visually upright by flipping clip Y.
    float clipX = viewX * f / safeAspect;
    float clipY = -viewY * f;
    float clipZ = (farPlane / (farPlane - nearPlane)) * viewZ
                - (farPlane * nearPlane / (farPlane - nearPlane));

    gl_Position = vec4(clipX, clipY, clipZ, viewZ);
    outWorldPos = worldPos;
    outWorldNormal = worldNormal;
    outBaseColor = faceColor(face);
    outCameraPos = cameraPos;
}
