package com.nishtahir

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.AndroidComponents
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.internal.tasks.factory.dependsOn
import kotlinx.serialization.Serializable
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.util.Properties

const val RUST_TASK_GROUP = "rust"

enum class ToolchainType {
    ANDROID_PREBUILT,
    ANDROID_GENERATED,
    DESKTOP,
}

// See https://forge.rust-lang.org/platform-support.html.
val toolchains = listOf(
        Toolchain("linux-x86-64",
                ToolchainType.DESKTOP,
                "x86_64-unknown-linux-gnu",
                "<compilerTriple>",
                "<binutilsTriple>",
                "desktop/linux-x86-64"),
        // This should eventually go away: the darwin-x86-64 target will supersede it.
        // https://github.com/mozilla/rust-android-gradle/issues/77
        Toolchain("darwin",
                ToolchainType.DESKTOP,
                "x86_64-apple-darwin",
                "<compilerTriple>",
                "<binutilsTriple>",
                "desktop/darwin"),
        Toolchain("darwin-x86-64",
                ToolchainType.DESKTOP,
                "x86_64-apple-darwin",
                "<compilerTriple>",
                "<binutilsTriple>",
                "desktop/darwin-x86-64"),
        Toolchain("darwin-aarch64",
                ToolchainType.DESKTOP,
                "aarch64-apple-darwin",
                "<compilerTriple>",
                "<binutilsTriple>",
                "desktop/darwin-aarch64"),
        Toolchain("win32-x86-64-msvc",
                ToolchainType.DESKTOP,
                "x86_64-pc-windows-msvc",
                "<compilerTriple>",
                "<binutilsTriple>",
                "desktop/win32-x86-64"),
        Toolchain("win32-x86-64-gnu",
                ToolchainType.DESKTOP,
                "x86_64-pc-windows-gnu",
                "<compilerTriple>",
                "<binutilsTriple>",
                "desktop/win32-x86-64"),
        Toolchain("arm",
                ToolchainType.ANDROID_GENERATED,
                "armv7-linux-androideabi",
                "arm-linux-androideabi",
                "arm-linux-androideabi",
                "android/armeabi-v7a"),
        Toolchain("arm64",
                ToolchainType.ANDROID_GENERATED,
                "aarch64-linux-android",
                "aarch64-linux-android",
                "aarch64-linux-android",
                "android/arm64-v8a"),
        Toolchain("x86",
                ToolchainType.ANDROID_GENERATED,
                "i686-linux-android",
                "i686-linux-android",
                "i686-linux-android",
                "android/x86"),
        Toolchain("x86_64",
                ToolchainType.ANDROID_GENERATED,
                "x86_64-linux-android",
                "x86_64-linux-android",
                "x86_64-linux-android",
                "android/x86_64"),
        Toolchain("arm",
                ToolchainType.ANDROID_PREBUILT,
                "armv7-linux-androideabi",  // This is correct.  "Note: For 32-bit ARM, the compiler is prefixed with
                "armv7a-linux-androideabi", // armv7a-linux-androideabi, but the binutils tools are prefixed with
                "arm-linux-androideabi",    // arm-linux-androideabi. For other architectures, the prefixes are the same
                "android/armeabi-v7a"),     // for all tools."  (Ref: https://developer.android.com/ndk/guides/other_build_systems#overview )
        Toolchain("arm64",
                ToolchainType.ANDROID_PREBUILT,
                "aarch64-linux-android",
                "aarch64-linux-android",
                "aarch64-linux-android",
                "android/arm64-v8a"),
        Toolchain("x86",
                ToolchainType.ANDROID_PREBUILT,
                "i686-linux-android",
                "i686-linux-android",
                "i686-linux-android",
                "android/x86"),
        Toolchain("x86_64",
                ToolchainType.ANDROID_PREBUILT,
                "x86_64-linux-android",
                "x86_64-linux-android",
                "x86_64-linux-android",
                "android/x86_64")
)

data class Ndk(val path: File, val version: String): java.io.Serializable {
    val versionMajor: Int
        get() = version.split(".").first().toInt()
}

@Serializable
data class Toolchain(val platform: String,
                     val type: ToolchainType,
                     val target: String,
                     val compilerTriple: String,
                     val binutilsTriple: String,
                     val folder: String): java.io.Serializable {
    fun cc(apiLevel: Int): File =
        if (System.getProperty("os.name").startsWith("Windows")) {
            if (type == ToolchainType.ANDROID_PREBUILT) {
                File("bin", "$compilerTriple$apiLevel-clang.cmd")
            } else {
                File("$platform-$apiLevel/bin", "$compilerTriple-clang.cmd")
            }
        } else {
            if (type == ToolchainType.ANDROID_PREBUILT) {
                File("bin", "$compilerTriple$apiLevel-clang")
            } else {
                File("$platform-$apiLevel/bin", "$compilerTriple-clang")
            }
        }

    fun cxx(apiLevel: Int): File =
        if (System.getProperty("os.name").startsWith("Windows")) {
            if (type == ToolchainType.ANDROID_PREBUILT) {
                File("bin", "$compilerTriple$apiLevel-clang++.cmd")
            } else {
                File("$platform-$apiLevel/bin", "$compilerTriple-clang++.cmd")
            }
        } else {
            if (type == ToolchainType.ANDROID_PREBUILT) {
                File("bin", "$compilerTriple$apiLevel-clang++")
            } else {
                File("$platform-$apiLevel/bin", "$compilerTriple-clang++")
            }
        }

    fun ar(apiLevel: Int, ndkVersionMajor: Int): File =
        if (ndkVersionMajor >= 23) {
            File("bin", "llvm-ar")
        } else if (type == ToolchainType.ANDROID_PREBUILT) {
            File("bin", "$binutilsTriple-ar")
        } else {
            File("$platform-$apiLevel/bin", "$binutilsTriple-ar")
        }
}

@Suppress("unused")
open class RustAndroidPlugin : Plugin<Project> {
    internal lateinit var cargoExtension: CargoExtension
    internal var generateToolchainsTask: TaskProvider<GenerateToolchainsTask>? = null
    internal lateinit var generateLinkerWrapperTask: TaskProvider<GenerateLinkerWrapperTask>
    internal lateinit var cargoBuildTask: TaskProvider<CargoBuildTask>
    internal lateinit var targetBuildTasks: Map<String, TaskProvider<CargoBuildTargetTask>>

    override fun apply(project: Project) = with(project) {
        val android = extensions.findByType(ApplicationExtension::class.java)
            ?: extensions.findByType(LibraryExtension::class.java)
            ?: throw GradleException("Neither ApplicationExtension or LibraryExtension found")
        val androidComponents = extensions[AndroidComponentsExtension::class]
        cargoExtension = extensions.create("cargo", CargoExtension::class.java)
        cargoExtension.localProperties = Properties().maybeLoadFromFile(project.rootDir, "local.properties")

        androidComponents.finalizeDsl {
            initCargoExtensionProperties(project, android)
            createCommonTasks(project)
            createTargetBuildTasks(project, getNdk(androidComponents))
        }

        with(androidComponents) {
            onVariants(selector().withBuildType("main")) { variant ->
                variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuildTask,
                    CargoBuildTask::jniOutputDirectory)
            }
            onVariants(selector().withBuildType("test")) { variant ->
                variant.sources.resources?.addGeneratedSourceDirectory(cargoBuildTask,
                    CargoBuildTask::testOutputDirectory)
            }
        }
    }

    private fun initCargoExtensionProperties(project: Project, android: CommonExtension) = with(cargoExtension) {

        if (module == null) {
            throw GradleException("module cannot be null")
        }

        if (libname == null) {
            throw GradleException("libname cannot be null")
        }

        // Allow to set targets, including per-project, in local.properties.
        localProperties.let {
            it.getProperty("rust.targets.${project.name}") ?: it.getProperty("rust.targets")
        }?.let { localTargets ->
            targets = localTargets.split(',').map { it.trim() }
        }

        if (targets == null) {
            throw GradleException("targets cannot be null")
        }

        if (apiLevels.isNotEmpty()) {
            if (apiLevel != null) {
                throw GradleException("Cannot set both `apiLevel` and `apiLevels`")
            }
        } else {
            val default = apiLevel ?: android.defaultConfig.minSdk ?: throw GradleException("minSdk not specified in defaultConfig")
            apiLevels = targets!!.associateWith { default }
        }

        val missingApiLevelTargets = targets!!.toSet().minus(apiLevels.keys)
        if (missingApiLevelTargets.isNotEmpty()) {
            throw GradleException("`apiLevels` missing entries for: $missingApiLevelTargets")
        }

        val oldApiLevelTargets = apiLevels.filterValues { it < 21 }
        if (oldApiLevelTargets.isNotEmpty()) {
            throw GradleException("`apiLevels` contains unsupported API levels < 21: $oldApiLevelTargets")
        }
    }

    private fun createCommonTasks(project: Project) = with(cargoExtension) {
        val usePrebuilt = localProperties.getProperty("rust.prebuiltToolchains")?.equals("true") ?:
            prebuiltToolchains ?: true

        if (!usePrebuilt) {
            generateToolchainsTask = project.tasks.register<GenerateToolchainsTask>("generateToolchains") {
                group = RUST_TASK_GROUP
                description = "Generate standard toolchain for given architectures"
            }
        }

        val rootBuildDir by project.rootBuildDirectory()
        val currentLocation = File(this::class.java.protectionDomain.codeSource.location.toURI()).path
        generateLinkerWrapperTask = project.rootProject.tasks.maybeRegister<GenerateLinkerWrapperTask>("generateLinkerWrapper") {
            group = RUST_TASK_GROUP
            description = "Generate shared linker wrapper script"

            from(project.rootProject.zipTree(currentLocation))
            include("**/linker-wrapper*")
            into(File(rootBuildDir, "linker-wrapper"))
            eachFile {
                it.path = it.path.replaceFirst("com/nishtahir", "")
            }
            filePermissions {
                it.unix("755")
            }
            includeEmptyDirs = false
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        }

        cargoBuildTask = project.tasks.register<CargoBuildTask>("cargoBuild") {
            group = RUST_TASK_GROUP
            description = "Build library (all targets)"
        }
    }

    private fun createTargetBuildTasks(project: Project, theNdk: Ndk) = with(cargoExtension) {
        val excludeType = if (generateToolchainsTask != null) {
            ToolchainType.ANDROID_PREBUILT
        } else {
            ToolchainType.ANDROID_GENERATED
        }
        targetBuildTasks = targets!!.associateWith { target ->
            val theToolchain = toolchains.filterNot { it.type == excludeType }.find { it.platform == target }
            if (theToolchain == null) {
                throw GradleException("Target $target is not recognized (recognized targets: ${toolchains.map { it.platform }.sorted()}).  Check `local.properties` and `build.gradle`.")
            }

            val task = project.tasks.register<CargoBuildTargetTask>("cargoBuild${target.capitalized()}") {
                group = RUST_TASK_GROUP
                description = "Build library ($target)"
                toolchain.set(theToolchain)
                ndk.set(theNdk)
                generateToolchainsTask?.let {
                    dependsOn(it)
                }
                dependsOn(generateLinkerWrapperTask)
            }
            cargoBuildTask.dependsOn(task)
            task
        }
    }

    private fun getNdk(androidComponents: AndroidComponents): Ndk {
        val ndkDir by androidComponents.sdkComponents.ndkDirectory
        val ndkPath = ndkDir.asFile
        val ndkVersion = Properties()
            .maybeLoadFromFile(ndkPath, "source.properties")
            .getProperty("Pkg.Revision", "0.0")

        val ndk = Ndk(path = ndkPath, version = ndkVersion)
        if (ndk.versionMajor < 19) {
            throw GradleException("NDK version < 19 not supported")
        }
        return ndk
    }
}
