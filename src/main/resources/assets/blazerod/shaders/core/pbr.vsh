#version 150

#moj_import <blazerod:instance.glsl>
#moj_import <blazerod:skin.glsl>
#moj_import <blazerod:morph.glsl>
#moj_import <blazerod:light.glsl>
#moj_import <blazerod:fog.glsl>
#moj_import <blazerod:projection.glsl>

in vec3 Position;
in vec4 Color;
// Texture UV
in vec2 UV0;
in vec3 Normal;

// Lightmap texture
uniform sampler2D SamplerLightMap;
uniform sampler2D SamplerOverlay;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;

out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec3 viewPosition;
out vec3 viewNormal;
// The two vanilla light directions moved into view space here; the fragment stage does not
// declare the InstanceData block, so it cannot reach ViewMatrix itself.
out vec3 lightDir0;
out vec3 lightDir1;

void main() {
    instance_t instance = get_instance();

    vec3 position = GET_MORPHED_VERTEX_POSITION(Position);
    vec4 color = GET_MORPHED_VERTEX_COLOR(Color);
    vec2 texCoord = GET_MORPHED_VERTEX_TEX_COORD(UV0);
    vec3 normal = GET_SKINNED_VERTEX_NORMAL(Normal);

    vec4 worldPosition = instance.model_mat * GET_SKINNED_VERTEX_POSITION(vec4(position, 1.0));
    vec4 view_position = ViewMatrix * worldPosition;
    viewPosition = view_position.xyz;
    viewNormal = normalize(mat3(ViewMatrix) * normalize(instance.model_normal_matrix * normal));

    lightDir0 = normalize(mat3(ViewMatrix) * Light0_Direction);
    lightDir1 = normalize(mat3(ViewMatrix) * Light1_Direction);

    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);

    gl_Position = ProjMat * view_position;

    vertexColor = color;
    lightMapColor = texelFetch(SamplerLightMap, instance.light_map_uv / 16, 0);
    overlayColor = texelFetch(SamplerOverlay, instance.overlay_uv, 0);
    texCoord0 = texCoord;
}
