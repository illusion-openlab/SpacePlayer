package tech.illusion.spaceplayer.playback

// User-facing label lives in ui/Labels.kt (Environment.label()) - a stored String field here
// would be a fixed Chinese literal baked into the enum, not locale-aware.
enum class Environment(val assetPath: String) {
    CINEMA("skyboxes/cinema_skybox.jpg"),
    STARRY_SKY("skyboxes/starry_skybox.jpg"),
    SEASIDE("skyboxes/seaside_skybox.jpg"),
}
