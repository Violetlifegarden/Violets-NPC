#version 150

// Authored for the 1.21.1 port. The distance and blend math is Minecraft 1.21.1's own fog math
// (linear_fog / fog_distance), exposed under the 1.21.2+ names BlazeRod's shaders call.

layout(std140) uniform Fog {
    vec3 FogColor;
    float FogShape;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
};

vec4 linear_fog(vec4 inColor, float vertexDistance, float fogStart, float fogEnd, vec4 fogColor) {
    if (vertexDistance <= fogStart) {
        return inColor;
    }

    float fogValue = vertexDistance < fogEnd ? smoothstep(fogStart, fogEnd, vertexDistance) : 1.0;
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

float fog_spherical_distance(vec3 pos) {
    return length(pos);
}

float fog_cylindrical_distance(vec3 pos) {
    float distXZ = length(pos.xz);
    float distY = abs(pos.y);
    return max(distXZ, distY);
}

vec4 apply_fog(
    vec4 color,
    float sphericalVertexDistance,
    float cylindricalVertexDistance,
    float environmentalStart,
    float environmentalEnd,
    float renderDistanceStart,
    float renderDistanceEnd,
    vec3 fogColor
) {
    float vertexDistance = FogShape == 0.0 ? sphericalVertexDistance : cylindricalVertexDistance;
    vec4 colorWithEnvironmentalFog =
        linear_fog(color, vertexDistance, environmentalStart, environmentalEnd, vec4(fogColor, 1.0));
    return linear_fog(
        colorWithEnvironmentalFog,
        vertexDistance,
        renderDistanceStart,
        renderDistanceEnd,
        vec4(fogColor, 1.0)
    );
}
