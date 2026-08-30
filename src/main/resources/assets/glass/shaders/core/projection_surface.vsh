#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

noperspective out vec2 screenUv;
flat out float revealDistance;

void main() {
    vec4 clipPosition = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position = clipPosition;
    screenUv = clipPosition.xy / clipPosition.w * 0.5 + 0.5;
    vec3 encodedDistance = floor(Color.rgb * 255.0 + 0.5);
    revealDistance = encodedDistance.r + encodedDistance.g * 256.0 + encodedDistance.b * 65536.0;
}
