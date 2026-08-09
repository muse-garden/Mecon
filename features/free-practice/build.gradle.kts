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
            api(project(":api"))
            api(project(":exploration"))
            api(project(":features:score-editing"))
            implementation(project(":core"))
            api(project(":theory"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    systemProperty("freepractice.trace.path", project.file("testdata/practice-trace.json").absolutePath)
    systemProperty("freepractice.timeline.trace.path", project.file("testdata/timeline-raw-input-trace.json").absolutePath)
    systemProperty(
        "freepractice.trace.write",
        (project.findProperty("freepractice.trace.write") as String?) ?: "false",
    )
}
