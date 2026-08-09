plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Forward snapshot-related Gradle properties to the test JVM as system properties.
// Usage:
//   ./gradlew :renderer:jvmTest -Pupdate-snapshots            # regenerate all snapshots
//   ./gradlew :renderer:jvmTest -Pupdate-snapshots -Pfilter=01 # regenerate matching scores only
tasks.withType<Test>().configureEach {
    if (project.hasProperty("update-snapshots")) {
        systemProperty("update-snapshots", "true")
    }
    if (project.hasProperty("debug-measure-44-slurs")) {
        systemProperty("debug-measure-44-slurs", "true")
    }
    project.findProperty("filter")?.let { systemProperty("snapshot-filter", it.toString()) }
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xcontext-receivers")
        }
    }
    js(IR) {
        browser()
        nodejs()
        compilerOptions {
            freeCompilerArgs.add("-Xcontext-receivers")
            moduleKind.set(org.jetbrains.kotlin.gradle.dsl.JsModuleKind.MODULE_ES)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":api"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.collections.immutable)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
