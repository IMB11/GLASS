#version 150

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord0;
flat out float revealDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
    vec3 encodedDistance = floor(Color.rgb * 255.0 + 0.5);
    revealDistance = encodedDistance.r + encodedDistance.g * 256.0 + encodedDistance.b * 65536.0;
}
