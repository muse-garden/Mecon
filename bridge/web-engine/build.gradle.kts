plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    js(IR) {
        browser()
        nodejs()
        binaries.library()
        generateTypeScriptDefinitions()
        compilerOptions {
            freeCompilerArgs.add("-Xcontext-receivers")
            moduleKind.set(org.jetbrains.kotlin.gradle.dsl.JsModuleKind.MODULE_ES)
        }
        compilations["main"].packageJson {
            customField("name", "@mecon/web-engine-kotlin")
            customField("version", "0.1.0")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":api"))
            implementation(project(":core"))
            implementation(project(":renderer"))
            implementation(project(":features:score-editing"))
            implementation(project(":features:free-practice"))
            implementation(project(":audio"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.collections.immutable)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.register<Sync>("prepareNpmPackage") {
    dependsOn("jsProductionLibraryCompileSync")
    from(layout.buildDirectory.dir("compileSync/js/main/productionLibrary/kotlin"))
    into(rootProject.layout.projectDirectory.dir("web/packages/web-renderer/kotlin"))
}
