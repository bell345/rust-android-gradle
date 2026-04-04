import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library") version("9.1.0")
    id("me.sigptr.rust-android")
}

version = "1.0"

extensions.configure<LibraryExtension> {
    namespace = "com.nishtahir.library"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 21
        testOptions.targetSdk = 35

        testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"

        aarMetadata {
            minCompileSdk = 21
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }
    }
}

cargo {
    module = "../rust"
    targets = listOf("x86_64", "arm64")
    libname = "rust"

    features {
        defaultAnd("foo", "bar")
        noDefaultBut("foo", "bar")
        all()
    }

    exec = { spec, toolchain ->
        spec.environment("TEST", "test")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    androidTestImplementation("com.android.support.test.espresso:espresso-core:3.0.2") {
        exclude("com.android.support:support-annotations")
    }
    implementation("com.android.support:appcompat-v7:28.0.0")
    testImplementation("junit:junit:4.13.2")
}
