plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.banv.iap"
    compileSdk = 35

    defaultConfig {
        // Play Billing 9 requires API 23.
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

kotlin { explicitApi() }

dependencies {
    // implementation: Play Billing still ships in the app (with its BILLING permission),
    // but its types never leak into this library's public API.
    implementation(libs.billing)
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "iap"
            afterEvaluate { from(components["release"]) }
        }
    }
}
