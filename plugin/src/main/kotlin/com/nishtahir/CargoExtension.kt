package com.nishtahir

import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.process.ExecSpec
import java.io.File
import java.util.*

sealed class Features {
    class All() : Features()

    data class DefaultAnd(val featureSet: Set<String>) : Features()

    data class NoDefaultBut(val featureSet: Set<String>) : Features()
}

@Suppress("unused")
data class FeatureSpec(var features: Features? = null) {
    fun all() {
        this.features = Features.All()
    }

    fun defaultAnd(vararg featureSet: String) {
        this.features = Features.DefaultAnd(featureSet.toSet())
    }

    fun noDefaultBut(vararg featureSet: String) {
        this.features = Features.NoDefaultBut(featureSet.toSet())
    }
}

// `CargoExtension` is documented in README.md.
open class CargoExtension {
    lateinit var localProperties: Properties

    var module: String? = null
    var libname: String? = null
    var targets: List<String>? = null
    var prebuiltToolchains: Boolean? = null
    var profile: String = "debug"
    var verbose: Boolean? = null
    var targetDirectory: String? = null
    var targetIncludes: Array<String>? = null
    var apiLevel: Int? = null
    var apiLevels: Map<String, Int> = mapOf()
    var extraCargoBuildArguments: List<String>? = null
    var generateBuildId: Boolean = false

    var exec: ((ExecSpec, Toolchain) -> Unit)? = null

    var featureSpec: FeatureSpec = FeatureSpec()

    @Suppress("unused")
    fun features(action: Action<FeatureSpec>) {
        action.execute(featureSpec)
    }

    val toolchainDirectory: File get() {
        // Share a single toolchain directory, if one is configured.  Prefer "local.properties"
        // to "ANDROID_NDK_TOOLCHAIN_DIR" to "$TMP/rust-android-ndk-toolchains".
        val propDir = getProperty("rust.androidNdkToolchainDir", "ANDROID_NDK_TOOLCHAIN_DIR")
        if (propDir != null) {
            return File(propDir).absoluteFile
        }
        val defaultDir = File(System.getProperty("java.io.tmpdir"), "rust-android-ndk-toolchains")
        return defaultDir.absoluteFile
    }

    var cargoCommand: String = ""
        get() {
            return field.ifEmpty {
                getProperty("rust.cargoCommand", "RUST_ANDROID_GRADLE_CARGO_COMMAND") ?: "cargo"
            }
        }

    var rustupChannel: String = ""
        get() {
            return field.ifEmpty {
                getProperty("rust.rustupChannel", "RUST_ANDROID_GRADLE_RUSTUP_CHANNEL") ?: ""
            }
        }

    var pythonCommand: String = ""
        get() {
            return field.ifEmpty {
                getProperty("rust.pythonCommand", "RUST_ANDROID_GRADLE_PYTHON_COMMAND") ?: "python"
            }
        }

    // Required so that we can parse the default triple out of `rustc --version --verbose`. Sadly,
    // there seems to be no way to get this information out of cargo directly. Failure to locate
    // this isn't fatal, however.
    var rustcCommand: String = ""
        get() {
            return field.ifEmpty {
                getProperty("rust.rustcCommand", "RUST_ANDROID_GRADLE_RUSTC_COMMAND") ?: "rustc"
            }
        }

    val autoConfigureClangSys: Boolean get() =
        // Set CLANG_PATH in the environment, so that bindgen (or anything
        // else using clang-sys in a build.rs) works properly, and doesn't
        // use host headers and such.
        getFlagProperty(
            "rust.autoConfigureClangSys",
            "RUST_ANDROID_GRADLE_AUTO_CONFIGURE_CLANG_SYS",
            // By default, only do this for non-desktop platforms. If we're
            // building for desktop, things should work out of the box.
            true
        )

    val cargoTargetDir: File get() =
        // CARGO_TARGET_DIR can be used to force the use of a global, shared target directory
        // across all rust projects on a machine. Use it if it's set, otherwise use the
        // configured `targetDirectory` value, and fall back to `${module}/target`.
        //
        // We also allow this to be specified in `local.properties`, not because this is
        // something you should ever need to do currently, but we don't want it to ruin anyone's
        // day if it turns out we're wrong about that.
        File(getProperty("rust.cargoTargetDir", "CARGO_TARGET_DIR")
            ?: targetDirectory
            ?: "${module}/target")


    internal fun getFlagProperty(camelCaseName: String, snakeCaseName: String, ifUnset: Boolean): Boolean {
        val propVal = getProperty(camelCaseName, snakeCaseName)
        if (propVal == "1" || propVal == "true") {
            return true
        }
        if (propVal == "0" || propVal == "false") {
            return false
        }
        if (propVal == null || propVal == "") {
            return ifUnset
        }
        throw GradleException("Illegal value for property \"$camelCaseName\" / \"$snakeCaseName\". Must be 0/1/true/false if set")
    }

    internal fun getProperty(propertyName: String, envVarName: String): String? =
        localProperties.getProperty(propertyName) ?: System.getenv(envVarName)
}
