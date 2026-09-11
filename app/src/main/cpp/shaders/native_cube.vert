#version 450

layout(location = 0) out vec3 outWorldPos;
layout(location = 1) out vec3 outWorldNormal;
layout(location = 2) out vec3 outCameraPos;
layout(location = 3) out vec2 outUv;
layout(location = 4) flat out int outMaterial;

layout(push_constant) uniform Push {
    float yaw;
    float pitch;
    float aspect;
    float cameraDistance;
    float preRotation;
    float stressMode;
} pc;

const float PI = 3.14159265358979323846;
const int CUBE_VERTS = 36;
const int FLOOR_VERTS = 6;
const int SPHERE_U = 32;
const int SPHERE_V = 16;
const int SPHERE_VERTS = SPHERE_U * SPHERE_V * 6;
const int QUALITY_VERTS = CUBE_VERTS + FLOOR_VERTS + SPHERE_VERTS * 3;
const int STRESS_U = 16;
const int STRESS_V = 8;
const int STRESS_SPHERE_VERTS = STRESS_U * STRESS_V * 6;
const int STRESS_COUNT = 64;

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

vec3 spherePoint(float u, float v) {
    float theta = u * 2.0 * PI;
    float phi = v * PI;
    float sp = sin(phi);
    return vec3(cos(theta) * sp, cos(phi), sin(theta) * sp);
}

void sphereVertex(
    int localIndex,
    int segU,
    int segV,
    vec3 center,
    float radius,
    int material,
    out vec3 worldPos,
    out vec3 worldNormal,
    out vec2 uv,
    out int mat
) {
    int cell = localIndex / 6;
    int corner = localIndex - cell * 6;
    int x = cell % segU;
    int y = cell / segU;

    vec2 q = quadCorner(corner) * 0.5 + 0.5;
    float u = (float(x) + q.x) / float(segU);
    float v = (float(y) + q.y) / float(segV);
    vec3 n = spherePoint(u, v);
    worldNormal = n;
    worldPos = center + n * radius;
    uv = vec2(u, v);
    mat = material;
}

void main() {
    vec3 worldPos;
    vec3 worldNormal;
    vec2 uv;
    int material;
    int index = gl_VertexIndex;

    if (index < CUBE_VERTS) {
        int face = index / 6;
        int corner = index - face * 6;
        uv = quadCorner(corner) * 0.5 + 0.5;
        // Bottom is exactly y=-1.0; floor is y=-1.01, so it is grounded with
        // a tiny anti-z-fighting gap instead of visibly floating.
        worldPos = facePosition(face, quadCorner(corner));
        worldNormal = faceNormal(face);
        material = 0; // brushed neutral metal/composite
    } else if (index < CUBE_VERTS + FLOOR_VERTS) {
        int corner = index - CUBE_VERTS;
        vec2 q = quadCorner(corner);
        uv = q * 0.5 + 0.5;
        worldPos = vec3(q.x * 7.5, -1.01, q.y * 7.5);
        worldNormal = vec3(0.0, 1.0, 0.0);
        material = 1; // concrete studio floor
    } else if (index < CUBE_VERTS + FLOOR_VERTS + SPHERE_VERTS) {
        sphereVertex(
            index - CUBE_VERTS - FLOOR_VERTS,
            SPHERE_U, SPHERE_V,
            vec3(-2.35, -0.18, -0.30), 0.82, 2,
            worldPos, worldNormal, uv, material);
    } else if (index < CUBE_VERTS + FLOOR_VERTS + SPHERE_VERTS * 2) {
        sphereVertex(
            index - CUBE_VERTS - FLOOR_VERTS - SPHERE_VERTS,
            SPHERE_U, SPHERE_V,
            vec3(2.25, -0.28, 0.20), 0.72, 3,
            worldPos, worldNormal, uv, material);
    } else if (index < QUALITY_VERTS) {
        sphereVertex(
            index - CUBE_VERTS - FLOOR_VERTS - SPHERE_VERTS * 2,
            SPHERE_U, SPHERE_V,
            vec3(0.15, -0.43, -2.35), 0.58, 4,
            worldPos, worldNormal, uv, material);
    } else {
        int stressLocal = index - QUALITY_VERTS;
        int objectIndex = stressLocal / STRESS_SPHERE_VERTS;
        int local = stressLocal - objectIndex * STRESS_SPHERE_VERTS;
        int gx = objectIndex % 8;
        int gz = objectIndex / 8;
        vec3 center = vec3(
            (float(gx) - 3.5) * 1.10,
            -0.64,
            -4.6 - float(gz) * 0.92
        );
        float radius = 0.31 + 0.035 * float((objectIndex * 7) % 5);
        int mat = 2 + (objectIndex % 3);
        sphereVertex(
            local,
            STRESS_U, STRESS_V,
            center, radius, mat,
            worldPos, worldNormal, uv, material);
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
    float farPlane = 100.0;
    vec2 clip = vec2(viewX * f / safeAspect, -viewY * f);

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
    outCameraPos = cameraPos;
    outUv = uv;
    outMaterial = material;
}
