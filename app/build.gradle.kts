import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}
/**
 * Downloads the on-device wake-word engine (sherpa-onnx, Apache-2.0) and its keyword-spotting model
 * (Apache-2.0) from their official GitHub releases, verifies pinned SHA-256 hashes, and extracts only
 * what the app needs. Nothing large is committed to git; a tampered or changed download fails the build.
 * See docs/decisions.md D21.
 */
abstract class FetchWakeWordAssets @Inject constructor(
    private val fs: FileSystemOperations,
    private val archives: ArchiveOperations,
) : DefaultTask() {
    @get:Input abstract val aarUrl: Property<String>
    @get:Input abstract val aarSha256: Property<String>
    @get:Input abstract val modelUrl: Property<String>
    @get:Input abstract val modelSha256: Property<String>
    @get:Input abstract val keywords: Property<String>
    @get:OutputFile abstract val aarFile: RegularFileProperty
    /** Generated assets root; the model lands in kws/ inside it. */
    @get:OutputDirectory abstract val assetsDir: DirectoryProperty
    @get:Internal abstract val cacheDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        val aar = aarFile.get().asFile
        if (!aar.exists() || sha256(aar) != aarSha256.get()) {
            download(aarUrl.get(), aar, aarSha256.get())
        }
        val tarball = File(cache, "kws-model.tar.bz2")
        if (!tarball.exists() || sha256(tarball) != modelSha256.get()) {
            download(modelUrl.get(), tarball, modelSha256.get())
        }
        val out = File(assetsDir.get().asFile, "kws")
        fs.delete { delete(out) }
        fs.copy {
            from(archives.tarTree(archives.bzip2(tarball)))
            include("**/encoder-*.int8.onnx", "**/decoder-*.int8.onnx", "**/joiner-*.int8.onnx", "**/tokens.txt")
            eachFile {
                // Flatten and give stable names the app can rely on.
                path = when {
                    name.startsWith("encoder") -> "encoder.int8.onnx"
                    name.startsWith("decoder") -> "decoder.int8.onnx"
                    name.startsWith("joiner") -> "joiner.int8.onnx"
                    else -> name
                }
            }
            includeEmptyDirs = false
            into(out)
        }
        File(out, "keywords.txt").writeText(keywords.get() + "\n")
    }

    private fun download(url: String, target: File, expected: String) {
        logger.lifecycle("Downloading ${target.name} from $url")
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        val actual = sha256(tmp)
        if (actual != expected) {
            tmp.delete()
            throw GradleException("SHA-256 mismatch for $url: expected $expected, got $actual")
        }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) throw GradleException("Could not move ${tmp.name} into place")
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

val sherpaVersion = "2.0.2"
val wakeWordAar = rootProject.layout.projectDirectory.file("third_party/sherpa-onnx-$sherpaVersion.aar")
val fetchWakeWordAssets = tasks.register<FetchWakeWordAssets>("fetchWakeWordAssets") {
    aarUrl.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar")
    aarSha256.set("6420cfb86c2ef8462dadd77b2202b7430487bdfb2bc715e9ce2864871209a80e")
    modelUrl.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2")
    modelSha256.set("f170013b4716e41b62b9bfd809687c207cef798ef9bc6534d524e17af9b6561a")
    // "HEY JARVIS" in the model's BPE pieces. Boost (:x) and threshold (#y) are tuned on a real phone.
    keywords.set("▁HE Y ▁JA R VI S :2.0 #0.25 @HEY_JARVIS")
    aarFile.set(wakeWordAar)
    assetsDir.set(layout.buildDirectory.dir("generated/wakeword-assets"))
    cacheDir.set(rootProject.layout.projectDirectory.dir("third_party/cache"))
}


android {
    namespace = "dev.jarvis"
    compileSdk {
        // Compose 1.13 requires compiling against API 37.1+.
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "dev.jarvis"
        minSdk = 29
        targetSdk = 37
        versionCode = 5
        versionName = "0.5.0-phase5"
        // The wake-word engine ships native code; all current phones are 64-bit ARM. Without this filter the
        // APK would carry four copies of the native libraries.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// Generated wake-word model (downloaded + verified by fetchWakeWordAssets) is added as an assets source.
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchWakeWordAssets, FetchWakeWordAssets::assetsDir)
    }
}

// Schema JSON is checked in so future versions can write tested migrations.
room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.viewmodel.compose)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.biometric)
    // On-device wake word engine (sherpa-onnx keyword spotting); fetched and hash-checked, not committed.
    implementation(files(wakeWordAar).builtBy(fetchWakeWordAssets))
    // LiteRT (TensorFlow Lite) runs the openWakeWord "hey jarvis" models (downloaded on demand, D27).
    implementation(libs.litert)
    implementation(libs.androidx.splashscreen)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    // Host-JVM SQLite so Room DAOs run in plain unit tests (no device or Robolectric).
    testImplementation(libs.sqlite.bundled.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
