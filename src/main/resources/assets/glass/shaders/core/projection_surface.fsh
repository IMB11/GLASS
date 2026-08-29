#version 150

uniform sampler2D Sampler0;
uniform float RevealProgress;

in vec2 texCoord0;
flat in float revealDistance;

out vec4 fragColor;

void main() {
    if (revealDistance > RevealProgress) {
        discard;
    }

    float distanceBehindEdge = RevealProgress - revealDistance;
    float whiteOpacity = clamp(1.0 - distanceBehindEdge * 0.5, 0.0, 1.0);
    vec3 projectionColor = texture(Sampler0, texCoord0).rgb;
    fragColor = vec4(mix(projectionColor, vec3(1.0), whiteOpacity), 1.0);
}
