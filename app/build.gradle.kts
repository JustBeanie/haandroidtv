import java.util.Properties
import com.android.build.api.variant.BuildConfigField

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kover)
    alias(libs.plugins.baselineprofile)
}

val releaseSigningProperties = Properties()
val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
}

fun releaseSigningProperty(name: String): String =
    releaseSigningProperties.getProperty(name)
        ?: error("keystore.properties is missing required '$name'.")

android {
    namespace = "dev.haquickaccess.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.haquickaccess.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("boolean", "BENCHMARK_MODE", "false")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    signingConfigs {
        if (releaseSigningProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(releaseSigningProperty("storeFile"))
                storePassword = releaseSigningProperty("storePassword")
                keyAlias = releaseSigningProperty("keyAlias")
                keyPassword = releaseSigningProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (releaseSigningProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
            buildConfigField("boolean", "BENCHMARK_MODE", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Keep the resolved runtime and test graph reproducible and make it
// inspectable by dependency vulnerability scanners.
dependencyLocking {
    lockAllConfigurations()
}

// Keep transitive build/test tooling on versions with current OSV fixes. These
// modules are pulled by Android lint and the Unified Test Platform rather than
// shipped in the application APK, but they still execute in the release CI.
configurations.configureEach {
    resolutionStrategy.force(
        "io.netty:netty-buffer:4.1.137.Final",
        "io.netty:netty-codec:4.1.137.Final",
        "io.netty:netty-codec-http:4.1.137.Final",
        "io.netty:netty-codec-http2:4.1.137.Final",
        "io.netty:netty-codec-socks:4.1.137.Final",
        "io.netty:netty-common:4.1.137.Final",
        "io.netty:netty-handler:4.1.137.Final",
        "io.netty:netty-handler-proxy:4.1.137.Final",
        "io.netty:netty-resolver:4.1.137.Final",
        "io.netty:netty-transport:4.1.137.Final",
        "io.netty:netty-transport-native-unix-common:4.1.137.Final",
        "org.apache.commons:commons-lang3:3.18.0",
        "org.apache.httpcomponents:httpclient:4.5.13",
        "org.bouncycastle:bcpkix-jdk18on:1.84",
        "org.bouncycastle:bcprov-jdk18on:1.84",
        "org.bouncycastle:bcutil-jdk18on:1.84",
    )
}

// The Baseline Profile plugin synthesizes these variants from release. Keep
// the deterministic fixture unavailable in production while enabling it in
// the generated APKs used for profile collection and macrobenchmarks.
listOf("benchmarkRelease", "nonMinifiedRelease").forEach { variantName ->
    androidComponents.onVariants(
        androidComponents.selector().withName(variantName),
    ) { variant ->
        variant.buildConfigFields?.put(
            "BENCHMARK_MODE",
            BuildConfigField("boolean", "true", "Enabled only for benchmarks."),
        )
    }
}

// Google Play accepts only signed Android App Bundles. Keeping this check out
// of assembleRelease preserves the documented unsigned APK workflow for local
// Shield sideloading. It must run before packageReleaseBundle, not merely
// bundleRelease, so an unsigned bundle is never produced.
val verifyReleaseSigning by tasks.registering {
    doLast {
        check(releaseSigningProperties.isNotEmpty()) {
            "A signed Play bundle requires a private root-level keystore.properties file. " +
                "See README.md for the required properties."
        }
    }
}

tasks.matching { it.name == "packageReleaseBundle" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.icons)
    implementation(libs.tv.material)

    implementation(libs.datastore.preferences)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tvprovider)
    implementation(libs.profileinstaller)
    "baselineProfile"(project(":benchmark"))

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}

kover {
    reports {
        variant("debug") {
            filters {
                includes {
                    classes(
                        "dev.haquickaccess.tv.data.CappedReconnectBackoff",
                        "dev.haquickaccess.tv.data.HomeAssistantProtocol",
                        "dev.haquickaccess.tv.data.UrlValidator",
                        "dev.haquickaccess.tv.domain.*",
                        "dev.haquickaccess.tv.ui.DashboardUiState",
                        "dev.haquickaccess.tv.ui.DashboardViewModel",
                    )
                }
            }
            verify {
                rule {
                    minBound(
                        80,
                        kotlinx.kover.gradle.plugin.dsl.CoverageUnit.LINE,
                        kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE,
                    )
                    minBound(
                        80,
                        kotlinx.kover.gradle.plugin.dsl.CoverageUnit.BRANCH,
                        kotlinx.kover.gradle.plugin.dsl.AggregationType.COVERED_PERCENTAGE,
                    )
                }
            }
        }
    }
}
