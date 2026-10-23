#version 330

uniform sampler2D InSampler;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform SaturationConfig {
    float Saturation;
};

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    float luma = dot(color.rgb, vec3(0.2126, 0.7152, 0.0722));
    fragColor = vec4(clamp(mix(vec3(luma), color.rgb, Saturation), 0.0, 1.0), color.a);
}
