import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing reads keystore.properties at the project root (git-ignored; see
// keystore.properties.example). Without it, release builds fall back to the debug key.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val hasReleaseKey = !keystoreProperties.getProperty("storeFile").isNullOrBlank()

// The version follows the git history, so every committed change gets a higher number:
// versionCode = number of commits, versionName = "0.1.<that number>". Android only installs an
// update with a higher versionCode. Raise VERSION_PREFIX by hand for a bigger release.
val VERSION_PREFIX = "0.1"
val commitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrElse {
    logger.warn("Couldn't count git commits (${it.message}): using version code 1.")
    1
}

android {
    namespace = "com.lumora.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lumora.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = commitCount
        versionName = "$VERSION_PREFIX.$commitCount"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("keystore.properties not found: signing the release build with the debug key.")
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

// APKs are named after the app and version, e.g. LumoraTV-0.1.19-release.apk.
base {
    archivesName = "LumoraTV-${android.defaultConfig.versionName}"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Updating without a cable (README): assembleRelease also writes update.json next to the APK, for
// Settings → Updates. Upload both files. The notes are -PupdateNotes="…", or the last commit's
// subject; -PupdateApkUrl=https://… writes an absolute APK address instead of the file name.
val writeUpdateJson by tasks.registering {
    description = "Writes update.json next to the release APK."
    val versionCode = commitCount
    val versionName = "$VERSION_PREFIX.$commitCount"
    val apkUrl = providers.gradleProperty("updateApkUrl").orElse("LumoraTV-$versionName-release.apk")
    val notes = providers.gradleProperty("updateNotes").orElse(
        providers.exec { commandLine("git", "log", "-1", "--format=%s"); isIgnoreExitValue = true }
            .standardOutput.asText.map { it.trim() },
    )
    val uncommitted = providers.exec { commandLine("git", "status", "--porcelain", "--untracked-files=no"); isIgnoreExitValue = true }
        .standardOutput.asText.map { it.isNotBlank() }
    val signedWithOwnKey = hasReleaseKey
    val output = layout.buildDirectory.file("outputs/apk/release/update.json")
    outputs.file(output)
    outputs.upToDateWhen { false }
    doLast {
        fun quote(text: String) = buildString {
            append('"')
            for (c in text) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c < ' ' -> append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
            append('"')
        }
        val json = "{\n" +
            "  \"versionCode\": $versionCode,\n" +
            "  \"versionName\": ${quote(versionName)},\n" +
            "  \"url\": ${quote(apkUrl.get())},\n" +
            "  \"notes\": ${quote(notes.get())}\n" +
            "}\n"
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(json)
        logger.lifecycle("Wrote ${file.path} for version $versionName ($versionCode).")
        if (!signedWithOwnKey) {
            logger.warn("This APK is signed with the debug key: it can't update a copy signed with your release key.")
        }
        if (uncommitted.get()) {
            logger.warn("Uncommitted changes: commit first, or this build has the same version code as the last commit.")
        }
    }
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(writeUpdateJson) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.tv.material)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kxml2)
    // Android's org.json is a stub in JVM unit tests; this is the real one.
    testImplementation(libs.json)
}
