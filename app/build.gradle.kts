import java.net.URI
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("androidx.room")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun apiBaseUrl(defaultUrl: String): String =
    System.getenv("PHOKARTA_API_BASE_URL")?.trim()?.takeIf(String::isNotEmpty)
        ?: providers.gradleProperty("PHOKARTA_API_BASE_URL").orNull?.trim()?.takeIf(String::isNotEmpty)
        ?: defaultUrl

fun optionalPolicyUrl(name: String, requireHttps: Boolean): String {
    val raw = System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: providers.gradleProperty(name).orNull?.trim()?.takeIf(String::isNotEmpty)
        ?: ""
    if (raw.isEmpty()) return ""
    if (requireHttps) {
        val parsed = runCatching { URI(raw) }.getOrNull()
        require(
            parsed?.isAbsolute == true &&
                parsed.scheme.equals("https", ignoreCase = true) &&
                !parsed.host.isNullOrBlank(),
        ) {
            "$name must be an absolute https:// URL when set."
        }
    }
    return raw.replace("\\", "\\\\").replace("\"", "\\\"")
}

fun com.android.build.api.dsl.ApplicationBuildType.addPolicyUrlFields(requireHttps: Boolean) {
    listOf(
        "PHOKARTA_TERMS_URL",
        "PHOKARTA_COMMUNITY_GUIDELINES_URL",
        "PHOKARTA_PRIVACY_URL",
    ).forEach { name ->
        buildConfigField("String", name, "\"${optionalPolicyUrl(name, requireHttps)}\"")
    }
}

fun signingValue(name: String): String? =
    System.getenv(name)?.trim()?.takeIf(String::isNotEmpty)
        ?: (findProperty(name) as String?)?.trim()?.takeIf(String::isNotEmpty)
        ?: keystoreProperties.getProperty(name)?.trim()?.takeIf(String::isNotEmpty)

val uploadStorePath = signingValue("PHOKARTA_UPLOAD_STORE_FILE")
val uploadStorePassword = signingValue("PHOKARTA_UPLOAD_STORE_PASSWORD")
val uploadKeyAlias = signingValue("PHOKARTA_UPLOAD_KEY_ALIAS")
val uploadKeyPassword = signingValue("PHOKARTA_UPLOAD_KEY_PASSWORD")
val uploadSigningValues = listOf(uploadStorePath, uploadStorePassword, uploadKeyAlias, uploadKeyPassword)
val uploadSigningProvidedCount = uploadSigningValues.count { it != null }
require(uploadSigningProvidedCount == 0 || uploadSigningProvidedCount == 4) {
    "Release signing is incomplete. Set all of PHOKARTA_UPLOAD_STORE_FILE, " +
        "PHOKARTA_UPLOAD_STORE_PASSWORD, PHOKARTA_UPLOAD_KEY_ALIAS, and " +
        "PHOKARTA_UPLOAD_KEY_PASSWORD (env, Gradle -P, or ignored keystore.properties), or set none of them."
}
val uploadStoreFile = uploadStorePath?.let { path ->
    val resolved = rootProject.file(path).takeIf { it.isFile } ?: file(path)
    require(resolved.isFile) { "PHOKARTA_UPLOAD_STORE_FILE does not exist: $path" }
    resolved
}
val hasUploadSigning = uploadStoreFile != null

android {
    namespace = "com.emirrkls.phokarta"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.emirrkls.phokarta"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0-beta.1"

        testInstrumentationRunner = "com.emirrkls.phokarta.HiltTestRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = uploadStoreFile
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = uploadKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            val baseUrl = apiBaseUrl("http://10.0.2.2:8080/").trimEnd('/') + "/"
            buildConfigField("String", "PHOKARTA_API_BASE_URL", "\"$baseUrl\"")
            addPolicyUrlFields(requireHttps = false)
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            val baseUrl = apiBaseUrl("https://api.phokarta.invalid/").let { url ->
                if (url.endsWith("/")) url else "${url.trimEnd('/')}/"
            }
            val parsedBaseUrl = runCatching { URI(baseUrl) }.getOrNull()
            require(
                parsedBaseUrl?.isAbsolute == true &&
                    parsedBaseUrl.scheme.equals("https", ignoreCase = true) &&
                    !parsedBaseUrl.host.isNullOrBlank() &&
                    baseUrl.endsWith("/"),
            ) {
                "Release PHOKARTA_API_BASE_URL must be an absolute https:// URL with a trailing slash."
            }
            buildConfigField("String", "PHOKARTA_API_BASE_URL", "\"$baseUrl\"")
            addPolicyUrlFields(requireHttps = true)
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasUploadSigning) {
                signingConfigs.getByName("upload")
            } else {
                logger.warn(
                    "PHOKARTA_UPLOAD_* is not set. Release artifacts are signed with the local debug " +
                        "keystore for structural verification only and are NOT Play-upload-ready.",
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

room {
    schemaDirectory("$projectDir/schemas")
}

secrets {
    propertiesFileName = "secrets.properties"
    defaultPropertiesFileName = "local.defaults.properties"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    val roomVersion = "2.7.2"
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.8.8")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("com.google.dagger:hilt-android:2.55")
    ksp("com.google.dagger:hilt-compiler:2.55")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.maps.android:maps-compose:6.4.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("com.google.dagger:hilt-android-testing:2.55")
    kspAndroidTest("com.google.dagger:hilt-compiler:2.55")
}

ksp {
    arg("dagger.fastInit", "enabled")
}
