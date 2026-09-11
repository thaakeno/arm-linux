#version 450
layout(location = 0) in vec3 inColor;
layout(location = 0) out vec4 outColor;

void main() {
    vec3 c = pow(inColor, vec3(0.9));
    outColor = vec4(c, 1.0);
}
