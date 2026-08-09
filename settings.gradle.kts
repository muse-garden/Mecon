rootProject.name = "mecon"

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        google()
        mavenCentral()
    }
}

includeBuild("../mecon-components")

include(":core")
include(":api")
include(":theory")
include(":exploration")
include(":renderer")
include(":bridge:web-engine")
include(":features:score-editing")
include(":features:free-practice")
include(":audio")
include(":performance-input")
include(":apps:desktop-ui-kit")
include(":apps:desktop")
include(":plugins:chord-analysis:core")
include(":plugins:chord-analysis:desktop")
include(":plugins:theory-analysis:desktop")
