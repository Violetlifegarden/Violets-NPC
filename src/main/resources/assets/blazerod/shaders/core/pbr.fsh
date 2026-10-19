#version 150

#moj_import <blazerod:fog.glsl>

uniform sampler2D SamplerBaseColor;
uniform sampler2D SamplerMetallicRoughness;
uniform sampler2D SamplerNormal;
uniform sampler2D SamplerOcclusion;
uniform sampler2D SamplerEmissive;

layout(std140) uniform PbrData {
    vec4 BaseColor;
    vec4 EmissiveFactor;
    float MetallicFactor;
    float RoughnessFactor;
    float NormalScale;
    float OcclusionStrength;
    int Flags;
};

in float sphericalVertexDistance;
in float cylindricalVertexDistance;

in vec4 vertexColor;
in vec4 lightMapColor;
in vec4 overlayColor;
in vec2 texCoord0;
in vec3 viewPosition;
in vec3 viewNormal;
in vec3 lightDir0;
in vec3 lightDir1;

out vec4 fragColor;

const float PI = 3.14159265359;
const int FLAG_NORMAL_TEXTURE = 1;

float distribution_ggx(float NdotH, float roughness) {
    float a = roughness * roughness;
    float a2 = a * a;
    float denom = NdotH * NdotH * (a2 - 1.0) + 1.0;
    return a2 / (PI * denom * denom);
}

float geometry_schlick_ggx(float NdotX, float roughness) {
    float r = roughness + 1.0;
    float k = (r * r) / 8.0;
    return NdotX / (NdotX * (1.0 - k) + k);
}

vec3 fresnel_schlick(float cosTheta, vec3 f0) {
    return f0 + (1.0 - f0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

vec3 evaluate_light(vec3 L, float NdotL, vec3 N, vec3 V, vec3 f0, float roughness, float NdotV) {
    vec3 H = normalize(V + L);
    float NdotH = max(dot(N, H), 0.0);
    float D = distribution_ggx(NdotH, roughness);
    float G = geometry_schlick_ggx(NdotV, roughness) * geometry_schlick_ggx(NdotL, roughness);
    vec3 F = fresnel_schlick(max(dot(H, V), 0.0), f0);
    return (D * G * F) / max(4.0 * NdotV * NdotL, 0.001);
}

// The vertex format has no tangent, so build the tangent frame from screen-space derivatives.
vec3 perturb_normal(vec3 n, vec3 position, vec2 uv) {
    vec3 dp1 = dFdx(position);
    vec3 dp2 = dFdy(position);
    vec2 duv1 = dFdx(uv);
    vec2 duv2 = dFdy(uv);
    vec3 dp2perp = cross(dp2, n);
    vec3 dp1perp = cross(n, dp1);
    vec3 t = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 b = dp2perp * duv1.y + dp1perp * duv2.y;
    float m = max(dot(t, t), dot(b, b));
    if (m <= 0.0) {
        return n;
    }
    float invMax = inversesqrt(m);
    mat3 tbn = mat3(t * invMax, b * invMax, n);
    vec3 mapN = texture(SamplerNormal, uv).xyz * 2.0 - 1.0;
    mapN.xy *= NormalScale;
    return normalize(tbn * mapN);
}

void main() {
    vec4 color = texture(SamplerBaseColor, texCoord0);
    if (color.a == 0.0) {
        discard;
    }
#ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
#endif
    color *= vertexColor * BaseColor;

    vec4 mrSample = texture(SamplerMetallicRoughness, texCoord0);
    float metallic = clamp(mrSample.b * MetallicFactor, 0.0, 1.0);
    float roughness = clamp(mrSample.g * RoughnessFactor, 0.04, 1.0);

    vec3 N = normalize(viewNormal);
    if ((Flags & FLAG_NORMAL_TEXTURE) != 0) {
        N = perturb_normal(N, viewPosition, texCoord0);
    }
    vec3 V = normalize(-viewPosition);

    vec3 f0 = mix(vec3(0.04), color.rgb, metallic);
    vec3 diffuseColor = color.rgb * (1.0 - metallic);
    float NdotV = max(dot(N, V), 0.001);

    // Vanilla's two-light setup: 0.4 ambient plus 0.6 per light, so a fully rough dielectric
    // matches minecraft_mix_light exactly and the PBR path only adds a specular term on top.
    float NdotL0 = max(dot(N, lightDir0), 0.0);
    float NdotL1 = max(dot(N, lightDir1), 0.0);
    vec3 result = diffuseColor * min(1.0, (NdotL0 + NdotL1) * 0.6 + 0.4);

    if (NdotL0 > 0.0) {
        result += evaluate_light(lightDir0, NdotL0, N, V, f0, roughness, NdotV) * NdotL0 * 0.6;
    }
    if (NdotL1 > 0.0) {
        result += evaluate_light(lightDir1, NdotL1, N, V, f0, roughness, NdotV) * NdotL1 * 0.6;
    }

    float ao = mix(1.0, texture(SamplerOcclusion, texCoord0).r, OcclusionStrength);
#ifndef EMISSIVE
    result *= lightMapColor.rgb * ao;
#endif
    result += texture(SamplerEmissive, texCoord0).rgb * EmissiveFactor.rgb;

#ifndef NO_OVERLAY
    result = mix(overlayColor.rgb, result, overlayColor.a);
#endif
    fragColor = apply_fog(vec4(result, color.a), sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
