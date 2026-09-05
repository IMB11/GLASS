#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

noperspective out vec2 screenUv;
out vec3 surfacePosition;
flat out vec4 revealDistances;
flat out vec4 projectionMask;

void main() {
    vec4 clipPosition = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position = clipPosition;
    screenUv = clipPosition.xy / clipPosition.w * 0.5 + 0.5;
    surfacePosition = Position;
    revealDistances = vec4(mod(UV0.x, 4096.0), floor(UV0.x / 4096.0), mod(UV0.y, 4096.0), floor(UV0.y / 4096.0));
    projectionMask = Color;
}
