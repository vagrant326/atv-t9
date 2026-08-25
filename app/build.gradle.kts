plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.vagrant326.atvt9"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.vagrant326.atvt9"
        minSdk = 23
        targetSdk = 35
        versionCode = (providers.gradleProperty("versionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("versionName").orNull ?: "0.0.0-dev"
    }

    /**
     * Two channels, and deliberately two *applications*. The dev build carries its own
     * applicationId, so it installs alongside the released one instead of replacing it — which
     * is the whole point: an experiment that takes over the d-pad has already cost this project
     * a TV, and the way to try the next one is with a working keyboard still installed.
     *
     * Each channel updates from its own releases. The tag prefix is what keeps them apart.
     */
    flavorDimensions += "channel"
    productFlavors {
        create("prod") {
            dimension = "channel"
            buildConfigField("String", "RELEASE_TAG_PREFIX", "\"v\"")
            buildConfigField("String", "RELEASE_ALIAS", "\"latest\"")
            buildConfigField("String", "RELEASE_ASSET", "\"atv-t9.apk\"")
        }
        create("dev") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
            buildConfigField("String", "RELEASE_TAG_PREFIX", "\"dev-\"")
            buildConfigField("String", "RELEASE_ALIAS", "\"latest-dev\"")
            buildConfigField("String", "RELEASE_ASSET", "\"atv-t9-dev.apk\"")
        }
    }

    // Release signing comes from the environment so the keystore never touches the
    // repository. Absent locally, in which case release builds stay unsigned rather
    // than silently falling back to the debug key - a debug-signed APK will not install
    // over a release-signed one, and finding that out on the TV is expensive.
    val keystoreFile = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (!keystoreFile.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    androidResources {
        // The dictionaries are already dense, so the packer wins little on them and costs a
        // decompression of the whole file at every keyboard start. Storing them uncompressed
        // trades a little download for a startup that does not scale with vocabulary size.
        noCompress += "bin"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    // Receiver registration flags only, for the install-result broadcast in the updater.
    implementation(libs.androidx.core)
}
