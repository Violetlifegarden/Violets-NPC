#version 150

// Authored for the 1.21.1 port: BlazeRod's shaders call the 1.21.2+ UBO-backed names, but
// Minecraft 1.21.1 ships only plain-uniform includes, so this file provides the same interface.

layout(std140) uniform Projection {
    mat4 ProjMat;
    vec2 ScreenSize;
};

vec4 projection_from_position(vec4 position) {
    vec4 projection = position * 0.5;
    projection.xy = vec2(projection.x + projection.w, projection.y + projection.w);
    projection.zw = position.zw;
    return projection;
}
