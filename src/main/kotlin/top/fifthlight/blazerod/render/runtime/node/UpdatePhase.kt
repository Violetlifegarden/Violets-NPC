package top.fifthlight.blazerod.render.runtime.node

sealed class UpdatePhase(
    val type: Type,
) {
    enum class Type {
        INFLUENCE_TRANSFORM_UPDATE,
        GLOBAL_TRANSFORM_PROPAGATION,
        RENDER_DATA_UPDATE,
        CAMERA_UPDATE,
    }

    data object InfluenceTransformUpdate : UpdatePhase(Type.INFLUENCE_TRANSFORM_UPDATE)

    data object GlobalTransformPropagation : UpdatePhase(Type.GLOBAL_TRANSFORM_PROPAGATION)

    data object RenderDataUpdate : UpdatePhase(Type.RENDER_DATA_UPDATE)

    data object CameraUpdate : UpdatePhase(Type.CAMERA_UPDATE)
}
