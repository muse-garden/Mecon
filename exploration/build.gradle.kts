plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    js(IR) {
        browser()
        nodejs()
        compilerOptions {
            moduleKind.set(org.jetbrains.kotlin.gradle.dsl.JsModuleKind.MODULE_ES)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":api"))
            implementation(project(":theory"))
            // UI-free chord event model used by textbook figuration examples to carry
            // the authoritative, user-visible harmonic analysis context.
            implementation(project(":plugins:chord-analysis:core"))
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
