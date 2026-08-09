plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

val forbiddenTransitionGeneratorPattern = "**/*ForbiddenTransitionGeneratorTest.class"

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

    // 禁忌表逐对求解开销很大，不进入日常 jvmTest；通过下方独立任务按需校验或重刷。
    tasks.named<Test>("jvmTest") {
        exclude(forbiddenTransitionGeneratorPattern)
    }

    sourceSets {
        commonMain.dependencies {
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

tasks.register<Test>("schoenbergForbiddenTransitionTest") {
    group = "verification"
    description = "按需校验或重刷勋伯格禁忌相邻进行表。"
    dependsOn("jvmTestClasses")

    val jvmTest = tasks.named<Test>("jvmTest").get()
    testClassesDirs = jvmTest.testClassesDirs
    classpath = jvmTest.classpath
    include(forbiddenTransitionGeneratorPattern)

    val writeFlag = (project.findProperty("schoenberg.forbidden.write") as String?) ?: "false"
    systemProperty("schoenberg.forbidden.write", writeFlag)
    shouldRunAfter(jvmTest)
}
