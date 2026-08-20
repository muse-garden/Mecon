plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // Android is the first shell. Presentation/session orchestration stays in commonMain so the
    // same code can later back iOS and Harmony adapters without translating editing semantics.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xcontext-receivers")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":api"))
            implementation(project(":features:score-editing"))
            implementation(project(":renderer"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
