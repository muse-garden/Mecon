import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // KMP modules - use jvmRuntimeElements for proper IDE resolution
    implementation(project(":core", configuration = "jvmRuntimeElements"))
    implementation(project(":api", configuration = "jvmRuntimeElements"))
    implementation(project(":theory", configuration = "jvmRuntimeElements"))
    implementation(project(":exploration", configuration = "jvmRuntimeElements"))
    implementation(project(":renderer", configuration = "jvmRuntimeElements"))
    implementation(project(":features:score-editing", configuration = "jvmRuntimeElements"))
    implementation(project(":features:free-practice", configuration = "jvmRuntimeElements"))
    implementation(project(":audio", configuration = "jvmRuntimeElements"))
    implementation(project(":performance-input", configuration = "jvmRuntimeElements"))
    implementation(project(":apps:desktop-ui-kit"))
    implementation(project(":plugins:chord-analysis:core", configuration = "jvmRuntimeElements"))
    implementation(project(":plugins:chord-analysis:desktop"))
    implementation(project(":plugins:theory-analysis:desktop"))
    implementation("com.mecon:mecon-components")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.kaml)
    // Vector PDF export: PDFBox writes the PDF container and content-stream drawing operators.
    // Music glyphs and text are emitted as filled vector outlines (see PdfContentWriter), so no
    // font embedding/subsetting is required — Apache-2.0, JVM-only, desktop app scope.
    implementation(libs.pdfbox)

    testImplementation(kotlin("test"))
}

val browserExportTestPattern = "com.mecon.desktop.service.FreePracticeBrowserExportTest"

tasks.test {
    useJUnitPlatform()
    System.getProperty("freepractice.desktop.golden.write")?.let {
        systemProperty("freepractice.desktop.golden.write", it)
    }
    // Compose's executable jar wiring can otherwise leave this JVM test task with dependency
    // outputs but without the desktop module's own main classes.
    classpath = files(sourceSets.main.get().output, classpath)
    // Needs an artifact the browser produced; run `freePracticeBrowserExportTest` after the web E2E.
    exclude("com/mecon/desktop/service/FreePracticeBrowserExportTest.class")
}

// Desktop half of the browser-export gate. The web E2E writes the archive, this reads it back;
// see docs/web-development.md. Kept out of `test` so the gate can never pass by doing nothing.
tasks.register<Test>("freePracticeBrowserExportTest") {
    group = "verification"
    description = "校验浏览器导出的 .mecon 能被桌面读回。需先运行 web 的 F1 E2E。"
    useJUnitPlatform()
    testClassesDirs = tasks.test.get().testClassesDirs
    classpath = tasks.test.get().classpath
    filter { includeTestsMatching(browserExportTestPattern) }
    val exportPath = (project.findProperty("freepractice.browser.export.path") as String?)
        ?: rootProject.file("web/build/e2e/browser-free-practice-export.mecon").absolutePath
    systemProperty("freepractice.browser.export.path", exportPath)
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.test)
}

// Forward the perf-tracing flag to the forked app JVM. The Compose `run` task launches a
// separate JVM, so `-Dmecon.perf=true` on the Gradle command line (set on the daemon) would
// otherwise never reach the app. See docs/performance/large-score-editing.md.
tasks.withType<JavaExec>().configureEach {
    System.getProperty("mecon.perf")?.let { systemProperty("mecon.perf", it) }
}

compose.desktop {
    application {
        mainClass = "com.mecon.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Mecon"
            packageVersion = "1.0.0"

            windows {
                menuGroup = "Mecon"
                upgradeUuid = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
            }

            macOS {
                bundleID = "com.mecon.desktop"
            }
        }
    }
}
