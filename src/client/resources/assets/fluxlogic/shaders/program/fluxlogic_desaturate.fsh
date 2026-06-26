#version 150

uniform sampler2D DiffuseSampler;
uniform float Strength;

in vec2 texCoord;
out vec4 fragColor;

// Rec. 709 luma — perceptual grayscale weighting.
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
    vec4 color = texture(DiffuseSampler, texCoord);
    float gray = dot(color.rgb, LUMA);
    // Blend each pixel toward its luminance by Strength. Highlighted entities
    // are drawn in a later pass / via the glow outline, so they stay vivid.
    vec3 desat = mix(color.rgb, vec3(gray), clamp(Strength, 0.0, 1.0));
    fragColor = vec4(desat, color.a);
}
