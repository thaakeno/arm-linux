#version 460

layout(location = 0) in vec3 inWorldPos;
layout(location = 1) in vec3 inWorldNormal;
layout(location = 2) in vec3 inCameraPos;
layout(location = 3) in vec2 inUv;
layout(location = 4) flat in int inMaterial;
layout(location = 5) flat in int inObject;
layout(location = 0) out vec4 outColor;

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

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 345.45));
    p += dot(p, p + 34.345);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1,0));
    float c = hash21(i + vec2(0,1));
    float d = hash21(i + vec2(1,1));
    return mix(mix(a,b,f.x), mix(c,d,f.x), f.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i=0;i<4;i++) {
        v += valueNoise(p) * a;
        p = p * 2.03 + vec2(17.1, 9.2);
        a *= 0.5;
    }
    return v;
}

vec3 materialBase(int m) {
    if (m == 0) return vec3(0.38,0.42,0.47);
    if (m == 1) return vec3(0.105,0.112,0.120);
    if (m == 2) return vec3(0.66,0.69,0.72);
    if (m == 3) return vec3(0.72,0.20,0.09);
    if (m == 4) return vec3(0.055,0.060,0.064);
    return vec3(0.44);
}

float materialMetallic(int m) {
    return (m == 0 || m == 2) ? 0.82 : 0.0;
}

float materialRoughness(int m) {
    if (m == 0) return 0.28;
    if (m == 1) return 0.78;
    if (m == 2) return 0.18;
    if (m == 3) return 0.33;
    if (m == 4) return 0.64;
    return 0.5;
}

vec3 environment(vec3 r) {
    float t = clamp(r.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 env = mix(vec3(0.018,0.021,0.025), vec3(0.46,0.51,0.58), smoothstep(0.04,0.96,t));
    vec3 a = normalize(vec3(-0.52,0.66,0.55));
    vec3 b = normalize(vec3(0.72,0.28,-0.48));
    env += vec3(1.0,0.94,0.85) * 0.55 * pow(max(dot(r,a),0.0), 22.0);
    env += vec3(0.48,0.59,0.76) * 0.20 * pow(max(dot(r,b),0.0), 28.0);
    return env;
}

vec3 aces(vec3 x) {
    const float a=2.51,b=0.03,c=2.43,d=0.59,e=0.14;
    return clamp((x*(a*x+b))/(x*(c*x+d)+e),0.0,1.0);
}

void main() {
    vec3 N = normalize(inWorldNormal);
    vec3 V = normalize(inCameraPos - inWorldPos);
    if (dot(N,V) <= 0.0) discard;

    float q = clamp(pc.quality / 100.0, 0.0, 1.0);
    vec3 base = materialBase(inMaterial);
    float metallic = materialMetallic(inMaterial);
    float rough = materialRoughness(inMaterial);

    if (q > 0.18) {
        vec2 p = inUv * mix(9.0, 55.0, q) + inWorldPos.xz * 3.0;
        float n = fbm(p * 0.7);
        float fine = valueNoise(p * 7.0);
        base *= mix(0.91,1.08,n) * mix(0.975,1.025,fine);
        rough = clamp(rough + (fine-0.5)*0.10*q, 0.08, 0.95);
    }

    if (inMaterial == 1 && q > 0.28) {
        vec2 cell = abs(fract(inWorldPos.xz * 0.5 + 0.5) - 0.5);
        float seam = smoothstep(0.475,0.495,max(cell.x,cell.y));
        base *= mix(1.0,0.72,seam);
    }

    vec3 lightPos = vec3(-3.7, 6.2, 4.8);
    vec3 Lvec = lightPos - inWorldPos;
    float dist2 = max(dot(Lvec,Lvec), 0.01);
    vec3 L = normalize(Lvec);
    vec3 H = normalize(L+V);
    float ndl = max(dot(N,L),0.0);
    float ndv = max(dot(N,V),0.001);
    float ndh = max(dot(N,H),0.0);
    float vdh = max(dot(V,H),0.0);

    float alpha = rough*rough;
    float a2 = alpha*alpha;
    float denom = ndh*ndh*(a2-1.0)+1.0;
    float D = a2 / max(3.14159265*denom*denom,0.0001);
    float k = (rough+1.0); k = k*k/8.0;
    float Gv = ndv/(ndv*(1.0-k)+k);
    float Gl = ndl/(ndl*(1.0-k)+k);
    vec3 F0 = mix(vec3(0.04),base,metallic);
    vec3 F = F0 + (1.0-F0)*pow(1.0-vdh,5.0);
    vec3 spec = (D*Gv*Gl*F) / max(4.0*ndv*ndl,0.001);
    vec3 kd = (1.0-F)*(1.0-metallic);

    float intensity = 58.0 / dist2;
    vec3 direct = (kd*base/3.14159265 + spec) * ndl * intensity * vec3(1.0,0.93,0.84);
    vec3 R = reflect(-V,N);
    vec3 ambient = environment(N) * base * (0.12 + 0.13*(1.0-metallic));
    vec3 iblSpec = environment(R) * F * mix(0.28,0.055,rough);

    vec3 color = ambient + iblSpec + direct;
    color = aces(color * 1.22);
    color = pow(color, vec3(1.0/2.2));
    outColor = vec4(color,1.0);
}
