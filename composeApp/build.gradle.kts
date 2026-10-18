import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.net.URI
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.application)
}

compose.resources { packageOfResClass = "pe.bpm.resources" }

val apiBaseUrl = providers.gradleProperty("apiBaseUrl")
    .orElse(providers.environmentVariable("API_BASE_URL"))
    .orElse("https://bpm-service-production.up.railway.app")
val generatedConfig = layout.buildDirectory.dir("generated/config/commonMain")
// Firebase publica esta configuración de cliente; nunca usar la cuenta Admin aquí.
val firebasePublicFile = rootProject.file("config/firebase.public.json")
val firebasePublic = JsonSlurper().parse(firebasePublicFile) as Map<*, *>
val firebaseApiKey = providers.gradleProperty("firebaseApiKey")
    .orElse(providers.environmentVariable("FIREBASE_API_KEY"))
    .orElse(firebasePublic["apiKey"] as String)
val generateAppConfig by tasks.registering {
    inputs.property("apiBaseUrl", apiBaseUrl)
    inputs.property("firebaseApiKey", firebaseApiKey)
    outputs.dir(generatedConfig)
    doLast {
        val url = apiBaseUrl.get().trimEnd('/')
        val parsed = URI(url)
        require(parsed.scheme == "https" && parsed.host != null && parsed.userInfo == null &&
            parsed.query == null && parsed.fragment == null && parsed.path.isNullOrEmpty()) {
            "API_BASE_URL debe ser un origen HTTPS público sin credenciales ni rutas."
        }
        val file = generatedConfig.get().file("pe/bpm/core/config/BuildConfig.kt").asFile
        val key = firebaseApiKey.get().trim()
        require(key.isEmpty() || key.matches(Regex("[A-Za-z0-9_-]{20,100}"))) {
            "FIREBASE_API_KEY debe ser la clave pública de Firebase, no un JSON ni un secreto Admin."
        }
        file.parentFile.mkdirs()
        file.writeText("package pe.bpm.core.config\n\ninternal const val NATIVE_API_BASE_URL = \"" + url + "\"\n" +
            "internal const val FIREBASE_API_KEY = \"" + key + "\"\n")
    }
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm()
    iosArm64()
    iosSimulatorArm64()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }
    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    sourceSets {
        commonMain {
            kotlin.srcDir(generatedConfig)
            dependencies {
                implementation("org.jetbrains.compose.runtime:runtime:${libs.versions.compose.get()}")
                implementation("org.jetbrains.compose.foundation:foundation:${libs.versions.compose.get()}")
                implementation("org.jetbrains.compose.ui:ui:${libs.versions.compose.get()}")
                implementation(libs.material3)
            implementation(libs.compose.resources)
                implementation(libs.lifecycle.viewmodel)
                implementation(libs.lifecycle.runtime)
                implementation(libs.navigation.compose)
                implementation(libs.coroutines.core)
                implementation(libs.serialization.json)
                implementation(libs.ktor.core)
                implementation(libs.ktor.negotiation)
                implementation(libs.ktor.json)
            }
        }
        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.ktor.okhttp)
        }
        iosMain.dependencies { implementation(libs.ktor.darwin) }
        wasmJsMain.dependencies { implementation(libs.ktor.js) }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.okhttp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.mock)
        }
    }
}
tasks.matching { it.name.startsWith("compile") || it.name.contains("SourcesJar") }
    .configureEach { dependsOn(generateAppConfig) }

android {
    namespace = "pe.bpm"
    compileSdk = 36
    defaultConfig {
        applicationId = "pe.bpm.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}
