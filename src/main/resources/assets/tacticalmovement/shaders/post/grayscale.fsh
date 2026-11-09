#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

layout(std140) uniform GrayConfig {
    float Amount;
    float Dark;
};

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 color = texture(InSampler, texCoord);
    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    // Slightly darker than the original with a touch more contrast, so the last stand feels heavy.
    luma = clamp((luma - 0.5) * 1.1 + 0.46, 0.0, 1.0);
    luma = pow(luma, 1.0 + (1.0 - Dark) * 1.6) * Dark;
    fragColor = vec4(mix(color.rgb, vec3(luma), Amount), 1.0);
}
