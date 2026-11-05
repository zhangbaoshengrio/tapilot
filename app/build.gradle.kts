import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class GenerateAndroidNotices : DefaultTask() {
    @get:org.gradle.api.tasks.InputDirectory
    abstract val licenseDirectory: org.gradle.api.file.DirectoryProperty
    @get:org.gradle.api.tasks.OutputDirectory
    abstract val outputDirectory: org.gradle.api.file.DirectoryProperty
    @get:org.gradle.api.tasks.InputFiles
    abstract val archives: org.gradle.api.file.ConfigurableFileCollection
    @get:org.gradle.api.tasks.Input
    abstract val coordinates: org.gradle.api.provider.MapProperty<String, String>

    @org.gradle.api.tasks.TaskAction
    fun collect() {
        val output = outputDirectory.get().dir("third-party/android").asFile
        output.deleteRecursively()
        output.mkdirs()
        val supplementalRoot = licenseDirectory.get().asFile
        @Suppress("UNCHECKED_CAST")
        val supplements = JsonSlurper().parse(supplementalRoot.resolve("android-supplements.json")) as List<Map<String, String>>
        val packages = archives.files.sortedBy { coordinates.get().getValue(it.absolutePath) }.map { archive ->
                val id = coordinates.get().getValue(archive.absolutePath)
                val target = output.resolve(id.replace(':', '/'))
                val files = mutableListOf<String>()
                fun isNotice(name: String) = Regex("(?i)^(license|licence|notice|copying|copyright)([._-].*)?$")
                    .matches(name.substringAfterLast('/'))
                fun retain(name: String, input: java.io.InputStream) {
                    val file = target.resolve(name).canonicalFile
                    check(file.toPath().startsWith(target.canonicalFile.toPath())) { "Invalid license entry" }
                    file.parentFile.mkdirs()
                    file.outputStream().use { input.copyTo(it) }
                    files.add(name)
                }
                ZipFile(archive).use { zip ->
                    zip.entries().asSequence().filterNot { it.isDirectory }.forEach { entry ->
                        if (isNotice(entry.name)) zip.getInputStream(entry).use { retain(entry.name, it) }
                        else if (entry.name.endsWith(".jar")) ZipInputStream(zip.getInputStream(entry)).use { nested ->
                            var child = nested.nextEntry
                            while (child != null) {
                                if (!child.isDirectory && isNotice(child.name)) retain("${entry.name}/${child.name}", nested)
                                nested.closeEntry()
                                child = nested.nextEntry
                            }
                        }
                    }
                }
                val supplement = supplements.singleOrNull { it["id"] == id }
                if (supplement != null) {
                    val notice = supplementalRoot.resolve(supplement.getValue("path").removePrefix("third-party/")).canonicalFile
                    check(notice.toPath().startsWith(supplementalRoot.canonicalFile.toPath())) { "Invalid supplemental notice path" }
                    val bytes = notice.readBytes()
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    check(hash == supplement["sha256"]) { "Supplemental notice checksum mismatch: $id" }
                    bytes.inputStream().use { retain("upstream-NOTICE.txt", it) }
                    supplementalRoot.resolve("notices/Apache-2.0.txt").inputStream().use { retain("Apache-2.0.txt", it) }
                }
                mapOf("id" to id, "files" to files, "license" to supplement?.get("license"), "reviewRequired" to files.isEmpty())
            }
        output.resolve("index.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(
            mapOf("schemaVersion" to 1, "packages" to packages))) + "\n")
    }
}

androidComponents.onVariants { variant ->
    val notices = tasks.register<GenerateAndroidNotices>("generate${variant.name.replaceFirstChar { it.uppercase() }}AndroidNotices") {
        licenseDirectory.set(rootProject.layout.projectDirectory.dir("third-party"))
        outputDirectory.set(layout.buildDirectory.dir("generated/third-party-assets/${variant.name}"))
        val dependencies = configurations.named("${variant.name}RuntimeClasspath")
        archives.from(dependencies)
        coordinates.set(provider {
            dependencies.get().resolvedConfiguration.resolvedArtifacts.associate {
                it.file.absolutePath to it.moduleVersion.id.toString()
            }
        })
    }
    variant.sources.assets?.addGeneratedSourceDirectory(notices, GenerateAndroidNotices::outputDirectory)
}

android {
    namespace = "lifeos.md.tapilot"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "lifeos.md.tapilot"
        // A test APK has one runner. Select the existing pairing harness explicitly.
        testInstrumentationRunner = providers.gradleProperty("tapilotTestRunner")
            .orElse("lifeos.md.tapilot.performance.NativeTaskPerformanceHarness").get()
        if (providers.gradleProperty("e2eVerification").isPresent) applicationIdSuffix = ".e2everify"
        val verificationLabel = when {
            providers.gradleProperty("e2eVerification").isPresent -> "Tapilot E2E Test"
            else -> null
        }
        manifestPlaceholders["tapilotAppLabel"] = verificationLabel ?: "@string/app_name"
        manifestPlaceholders["tapilotControlLabel"] = verificationLabel?.let { "$it · Device control" }
            ?: "@string/accessibility_service_name"
        minSdk = 30
        targetSdk = 35
        // An older local build exercises the available-update UI against the real published feed.
        // This verification package is never distributed as a release.
        val updateVerification = providers.gradleProperty("updateVerification").isPresent
        versionCode = if (updateVerification) 1 else 12
        versionName = if (updateVerification) "0.1.0-update-test" else "0.1.11"
    }

    val releaseStore = providers.environmentVariable("TAPILOT_SIGNING_STORE")
    signingConfigs {
        if (releaseStore.isPresent) create("production") {
            storeFile = file(releaseStore.get())
            storePassword = providers.environmentVariable("TAPILOT_SIGNING_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("TAPILOT_SIGNING_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("TAPILOT_SIGNING_KEY_PASSWORD").orNull
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            signingConfig = signingConfigs.findByName("production")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    // Upgrade-compatible beta: current installed certificate, with debugging disabled.
    buildTypes.create("beta") {
        initWith(buildTypes.getByName("release"))
        signingConfig = signingConfigs.getByName("debug")
        isDebuggable = false
        matchingFallbacks += "release"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; aidl = true }

    // Offline reports still need their export helper and branding assets.
    sourceSets["main"].assets.srcDir("../public")
    sourceSets["main"].jniLibs.srcDir("../.cache/native")
    sourceSets["test"].resources.srcDir("../tests/fixtures")

    packaging {
        jniLibs.useLegacyPackaging = true
    }

    bundle {
        language.enableSplit = false
    }
}

dependencies {
    debugImplementation("com.github.osservatorionessuno:libadb-android-bc:3.5.1")
    debugImplementation("org.bouncycastle:bcpkix-jdk15to18:1.84")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2025.03.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val verifyBrandTheme by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", "scripts/generate-brand.mjs", "--check")
}

val verifyReportAssets by tasks.registering {
    val reportFiles = listOf("report-export.js", "tapilot-icon.png").map { rootProject.file("public/$it") }
    inputs.files(reportFiles)
    doLast { check(reportFiles.all { it.isFile }) { "Missing offline report assets" } }
}

val verifyNodeRuntimeAssets by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine("node", "scripts/runtime-integrity.mjs")
    val nodeBundle = rootProject.file("app/src/main/assets/node-runtime-bundle.zip")
    val nodeManifest = rootProject.file("app/src/main/assets/node-runtime-manifest.json")
    val nodeBinary = rootProject.file(".cache/native/arm64-v8a/libnodebin.so")
    inputs.files(nodeBundle, nodeManifest, nodeBinary)
    doLast {
        check(nodeBundle.isFile && nodeManifest.isFile && nodeBinary.isFile) {
            "Missing embedded Node runtime. Run `pnpm --filter tapilot build:android`."
        }
    }
}

tasks.named("preBuild") {
    dependsOn(verifyBrandTheme, verifyReportAssets)
}

val verifyReleaseSigning by tasks.registering {
    doLast {
        check(listOf("TAPILOT_SIGNING_STORE", "TAPILOT_SIGNING_STORE_PASSWORD", "TAPILOT_SIGNING_KEY_ALIAS", "TAPILOT_SIGNING_KEY_PASSWORD", "TAPILOT_SIGNING_CERT_SHA256")
            .all { !System.getenv(it).isNullOrBlank() }) { "Release requires explicit TAPILOT_SIGNING_* credentials; use assembleBeta for compatible beta updates." }
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild" || name == "packageRelease") dependsOn(verifyReleaseSigning)
    if (name in listOf("packageDebug", "packageRelease", "packageBeta")) {
        dependsOn(verifyNodeRuntimeAssets)
    }
    if (name == "assembleDebug") {
        doLast {
            ZipFile(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile).use { apk ->
                val names = apk.entries().asSequence().map { it.name }.toSet()
                check("assets/report-export.js" in names && "assets/tapilot-icon.png" in names) {
                    "Native APK is missing offline report assets"
                }
                check("assets/index.html" !in names && names.none { it.startsWith("assets/static/") }) {
                    "Native APK must not include the legacy web UI"
                }
            }
        }
    }
}

// Only the desktop comparison harness consumes this JVM test classpath.
// It reuses production request/verification policies without duplicating them in JS.
tasks.register("prepareBenchmarkCodec") {
    dependsOn("testDebugUnitTest")
    val destination = layout.buildDirectory.file("benchmark-codec/classpath.txt")
    outputs.file(destination)
    inputs.files(provider { tasks.named<Test>("testDebugUnitTest").get().classpath })
    doLast {
        val unitTests = tasks.named<Test>("testDebugUnitTest").get()
        destination.get().asFile.apply {
            parentFile.mkdirs()
            writeText(unitTests.classpath.asPath)
        }
    }
}
