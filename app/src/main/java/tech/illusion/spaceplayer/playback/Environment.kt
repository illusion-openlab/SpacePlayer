package tech.illusion.spaceplayer.playback

enum class Environment(val assetPath: String, val label: String) {
    CINEMA("skyboxes/cinema_skybox.jpg", "电影院"),
    STARRY_SKY("skyboxes/starry_skybox.jpg", "星空"),
    SEASIDE("skyboxes/seaside_skybox.jpg", "海景"),
}
