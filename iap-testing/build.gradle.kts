plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace = "com.banv.iap.testing"
    compileSdk = 35

    defaultConfig { minSdk = 23 }

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
    api(project(":iap"))
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "iap-testing"
            afterEvaluate { from(components["release"]) }
        }
    }
}
