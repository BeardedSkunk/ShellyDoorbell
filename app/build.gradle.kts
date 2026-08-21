plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "de.beardedskunk.shellydoorbell"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.beardedskunk.shellydoorbell"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "1.2.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Debug-Signatur, damit das Release-APK ohne eigenen Keystore per USB
            // installierbar ist (privates Projekt, kein Play-Store-Vertrieb).
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// shelly/doorbell.js wird als Asset gebuendelt: die App vergleicht die Version
// auf dem Geraet und spielt das Script bei Bedarf selbst ein. Der Umweg ueber
// einen Task haelt shelly/ als einzige Quelle (kein Duplikat im Repo).
abstract class CopyDoorbellScriptTask : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        source.get().asFile.copyTo(File(outputDir.get().asFile, "doorbell.js"), overwrite = true)
    }
}

val copyDoorbellScript = tasks.register<CopyDoorbellScriptTask>("copyDoorbellScript") {
    source.set(rootProject.layout.projectDirectory.file("shelly/doorbell.js"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyDoorbellScript, CopyDoorbellScriptTask::outputDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    testImplementation(libs.junit)
}
