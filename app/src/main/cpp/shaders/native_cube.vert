#version 450

layout(location = 0) out vec3 outColor;

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
    if (face == 0) return vec3( q.x,  q.y,  1.0); // front
    if (face == 1) return vec3(-q.x,  q.y, -1.0); // back
    if (face == 2) return vec3( 1.0,  q.y, -q.x); // right
    if (face == 3) return vec3(-1.0,  q.y,  q.x); // left
    if (face == 4) return vec3( q.x,  1.0, -q.y); // top
    return vec3(q.x, -1.0, q.y);                  // bottom
}

vec3 faceColor(int face) {
    if (face == 0) return vec3(0.18, 0.95, 0.72);
    if (face == 1) return vec3(0.20, 0.52, 1.00);
    if (face == 2) return vec3(0.92, 0.35, 0.40);
    if (face == 3) return vec3(0.64, 0.36, 1.00);
    if (face == 4) return vec3(1.00, 0.76, 0.22);
    return vec3(0.20, 0.78, 0.92);
}

void main() {
    int face = gl_VertexIndex / 6;
    int corner = gl_VertexIndex - face * 6;
    vec2 q = quadCorner(corner);
    vec3 p = facePosition(face, q);

    float cy = cos(pc.yaw);
    float sy = sin(pc.yaw);
    float cx = cos(pc.pitch);
    float sx = sin(pc.pitch);

    p = vec3(cy * p.x + sy * p.z, p.y, -sy * p.x + cy * p.z);
    p = vec3(p.x, cx * p.y - sx * p.z, sx * p.y + cx * p.z);

    float zoom = clamp(6.2 / max(pc.cameraDistance, 0.1), 0.55, 1.95);
    float scale = 0.48 * zoom;
    float safeAspect = max(pc.aspect, 0.01);

    // Keep every vertex safely inside Vulkan clip depth. Geometry is generated
    // arithmetically from gl_VertexIndex instead of dynamically indexing large
    // constant arrays, avoiding a driver-sensitive path on some Adreno builds.
    gl_Position = vec4(
        p.x * scale / safeAspect,
        -p.y * scale,
        0.5,
        1.0
    );
    outColor = faceColor(face);
}
