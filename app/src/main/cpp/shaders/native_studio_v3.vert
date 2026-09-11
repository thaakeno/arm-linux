#version 460

layout(location = 0) out vec3 outWorldPos;
layout(location = 1) out vec3 outWorldNormal;
layout(location = 2) out vec3 outCameraPos;
layout(location = 3) out vec2 outUv;
layout(location = 4) flat out int outMaterial;
layout(location = 5) flat out int outObject;

struct BodyGpu {
    vec4 posRad;
    vec4 velMass;
    vec4 extra;
    ivec4 meta;
};

layout(set = 0, binding = 0, std430) readonly buffer Bodies {
    BodyGpu bodies[];
};

layout(push_constant) uniform Push {
    float yaw;
    float pitch;
    float aspect;
    float cameraDistance;
    float preRotation;
    float quality;
    float rtEnabled;
    float time;
} pc;

const float PI = 3.14159265358979323846;
const int CUBE_VERTS = 36;
const int FLOOR_VERTS = 6;
const int SPHERE_U = 32;
const int SPHERE_V = 16;
const int SPHERE_VERTS = SPHERE_U * SPHERE_V * 6;
const int QUALITY_SPHERES = 3;
const int QUALITY_VERTS = CUBE_VERTS + FLOOR_VERTS + SPHERE_VERTS * QUALITY_SPHERES;
const int STRESS_U = 16;
const int STRESS_V = 8;
const int STRESS_SPHERE_VERTS = STRESS_U * STRESS_V * 6;

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

void sphereVertex(int localIndex, int segU, int segV, int bodyIndex,
                  out vec3 worldPos, out vec3 worldNormal, out vec2 uv,
                  out int mat, out int objectId) {
    int cell = localIndex / 6;
    int corner = localIndex - cell * 6;
    int x = cell % segU;
    int y = cell / segU;
    vec2 q = quadCorner(corner) * 0.5 + 0.5;
    float u = (float(x) + q.x) / float(segU);
    float v = (float(y) + q.y) / float(segV);
    vec3 n = spherePoint(u, v);

    BodyGpu b = bodies[bodyIndex];
    vec3 local = n * b.posRad.w;
    if (b.meta.y == 1) {
        float compression = clamp(b.extra.x, -0.18, 0.34);
        float sy = max(0.58, 1.0 - compression);
        float sxz = inversesqrt(sy);
        local *= vec3(sxz, sy, sxz);
        n = normalize(n / vec3(sxz, sy, sxz));
    }
    worldPos = b.posRad.xyz + local;
    worldNormal = n;
    uv = vec2(u, v);
    mat = b.meta.x;
    objectId = bodyIndex + 2;
}

void main() {
    vec3 worldPos;
    vec3 worldNormal;
    vec2 uv;
    int material;
    int objectId;
    int index = gl_VertexIndex;

    if (index < CUBE_VERTS) {
        int face = index / 6;
        int corner = index - face * 6;
        uv = quadCorner(corner) * 0.5 + 0.5;
        worldPos = facePosition(face, quadCorner(corner));
        worldNormal = faceNormal(face);
        material = 0;
        objectId = 1;
    } else if (index < CUBE_VERTS + FLOOR_VERTS) {
        int corner = index - CUBE_VERTS;
        vec2 q = quadCorner(corner);
        uv = q * 0.5 + 0.5;
        worldPos = vec3(q.x * 8.0, -1.01, q.y * 8.0);
        worldNormal = vec3(0.0, 1.0, 0.0);
        material = 1;
        objectId = 0;
    } else if (index < QUALITY_VERTS) {
        int sphereBlock = (index - CUBE_VERTS - FLOOR_VERTS) / SPHERE_VERTS;
        int local = (index - CUBE_VERTS - FLOOR_VERTS) - sphereBlock * SPHERE_VERTS;
        sphereVertex(local, SPHERE_U, SPHERE_V, sphereBlock,
                     worldPos, worldNormal, uv, material, objectId);
    } else {
        int stressLocal = index - QUALITY_VERTS;
        int bodyIndex = 3 + stressLocal / STRESS_SPHERE_VERTS;
        int local = stressLocal - (bodyIndex - 3) * STRESS_SPHERE_VERTS;
        sphereVertex(local, STRESS_U, STRESS_V, bodyIndex,
                     worldPos, worldNormal, uv, material, objectId);
    }

    float cp = cos(pc.pitch);
    vec3 cameraPos = pc.cameraDistance * vec3(
        cp * sin(pc.yaw), sin(pc.pitch), cp * cos(pc.yaw));
    vec3 forward = normalize(-cameraPos);
    vec3 right = normalize(cross(forward, vec3(0.0, 1.0, 0.0)));
    vec3 up = normalize(cross(right, forward));

    vec3 rel = worldPos - cameraPos;
    float viewX = dot(rel, right);
    float viewY = dot(rel, up);
    float viewZ = dot(rel, forward);

    float f = 1.0 / tan(radians(43.0) * 0.5);
    vec2 clip = vec2(viewX * f / max(pc.aspect, 0.01), -viewY * f);
    int rot = int(pc.preRotation + 0.5);
    if (rot == 1) clip = vec2(-clip.y, clip.x);
    else if (rot == 2) clip = -clip;
    else if (rot == 3) clip = vec2(clip.y, -clip.x);

    float nearPlane = 0.10;
    float farPlane = 120.0;
    float clipZ = (farPlane / (farPlane - nearPlane)) * viewZ
                - (farPlane * nearPlane / (farPlane - nearPlane));

    gl_Position = vec4(clip, clipZ, viewZ);
    outWorldPos = worldPos;
    outWorldNormal = worldNormal;
    outCameraPos = cameraPos;
    outUv = uv;
    outMaterial = material;
    outObject = objectId;
}
