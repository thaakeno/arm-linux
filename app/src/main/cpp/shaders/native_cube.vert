#version 450

layout(location = 0) out vec3 outWorldPos;
layout(location = 1) out vec3 outWorldNormal;
layout(location = 2) out vec3 outBaseColor;
layout(location = 3) out vec3 outCameraPos;
layout(location = 4) out vec2 outFaceUv;
layout(location = 5) flat out int outMaterial;

layout(push_constant) uniform Push {
    float yaw;
    float pitch;
    float aspect;
    float cameraDistance;
    float preRotation;
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

void main() {
    vec3 worldPos;
    vec3 worldNormal;
    vec3 baseColor;
    vec2 uv;
    int material;

    if (gl_VertexIndex < 36) {
        int face = gl_VertexIndex / 6;
        int corner = gl_VertexIndex - face * 6;
        uv = quadCorner(corner);
        worldPos = facePosition(face, uv);
        worldNormal = faceNormal(face);
        // One coherent neutral material. Face readability now comes from the
        // lighting and micro-surface response rather than debug face colors.
        baseColor = vec3(0.46, 0.49, 0.52);
        material = 0;
    } else {
        int corner = gl_VertexIndex - 36;
        uv = quadCorner(corner);
        worldPos = vec3(uv.x * 6.5, -1.30, uv.y * 6.5);
        worldNormal = vec3(0.0, 1.0, 0.0);
        baseColor = vec3(0.105, 0.112, 0.120);
        material = 1;
    }

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
    float f = 1.0 / tan(radians(43.0) * 0.5);
    float nearPlane = 0.10;
    float farPlane = 80.0;

    vec2 clip = vec2(viewX * f / safeAspect, -viewY * f);

    // Android Vulkan pre-rotation. The swapchain is allocated in the display's
    // identity orientation and currentTransform is applied here in clip space.
    // 0 = identity, 1 = 90 degrees, 2 = 180 degrees, 3 = 270 degrees.
    int rot = int(pc.preRotation + 0.5);
    if (rot == 1) {
        clip = vec2(-clip.y, clip.x);
    } else if (rot == 2) {
        clip = -clip;
    } else if (rot == 3) {
        clip = vec2(clip.y, -clip.x);
    }

    float clipZ = (farPlane / (farPlane - nearPlane)) * viewZ
                - (farPlane * nearPlane / (farPlane - nearPlane));

    gl_Position = vec4(clip, clipZ, viewZ);
    outWorldPos = worldPos;
    outWorldNormal = worldNormal;
    outBaseColor = baseColor;
    outCameraPos = cameraPos;
    outFaceUv = uv;
    outMaterial = material;
}
