#version 150

uniform sampler2D Projection0;
uniform sampler2D Projection1;
uniform sampler2D Projection2;
uniform sampler2D Projection3;
uniform vec3 ProjectorPosition0;
uniform vec3 ProjectorPosition1;
uniform vec3 ProjectorPosition2;
uniform vec3 ProjectorPosition3;
uniform vec4 RevealProgress;
uniform float LoadingOpacity;
uniform float CrossfadeWidth;

noperspective in vec2 screenUv;
in vec3 surfacePosition;
flat in vec4 revealDistances;
flat in vec4 projectionMask;

out vec4 fragColor;

vec3 projectionColor(int index) {
    if (index == 0) {
        return texture(Projection0, screenUv).rgb;
    }
    if (index == 1) {
        return texture(Projection1, screenUv).rgb;
    }
    if (index == 2) {
        return texture(Projection2, screenUv).rgb;
    }
    return texture(Projection3, screenUv).rgb;
}

void main() {
    vec4 visible = projectionMask * step(revealDistances, RevealProgress);
    if (LoadingOpacity >= 0.0) {
        if (dot(visible, vec4(1.0)) > 0.0) {
            discard;
        }
        fragColor = vec4(1.0, 1.0, 1.0, LoadingOpacity);
        return;
    }

    if (dot(visible, vec4(1.0)) == 0.0) {
        discard;
    }

    vec3 positions[4] = vec3[4](ProjectorPosition0, ProjectorPosition1, ProjectorPosition2, ProjectorPosition3);
    vec3 color = vec3(0.0);
    float totalWeight = 0.0;
    for (int index = 0; index < 4; index++) {
        if (visible[index] == 0.0) {
            continue;
        }
        float weight = 1.0;
        for (int other = 0; other < 4; other++) {
            if (other == index || visible[other] == 0.0) {
                continue;
            }
            vec3 separation = positions[other] - positions[index];
            float separationLength = length(separation);
            float midpointDistance = separationLength > 0.0001
                    ? dot((positions[index] + positions[other]) * 0.5 - surfacePosition, separation / separationLength)
                    : 0.0;
            weight *= smoothstep(-CrossfadeWidth * 0.5, CrossfadeWidth * 0.5, midpointDistance);
        }
        if (weight > 0.0) {
            float whiteOpacity = clamp(1.0 - (RevealProgress[index] - revealDistances[index]) * 0.5, 0.0, 1.0);
            color += mix(projectionColor(index), vec3(1.0), whiteOpacity) * weight;
            totalWeight += weight;
        }
    }
    fragColor = vec4(color / totalWeight, 1.0);
}
