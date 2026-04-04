package com.nishtahir

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.LogLevel
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

abstract class CargoBuildTargetTask : DefaultTask() {
    @get:Input
    abstract val toolchain: Property<Toolchain>

    @get:Input
    abstract val ndk: Property<Ndk>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        outputDir.set(toolchain.flatMap {
            project.layout.buildDirectory.dir("rustJniLibs/${it.folder}")
        })
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    @Suppress("unused")
    @TaskAction
    fun build() = with(project) {
        extensions[CargoExtension::class].apply {
            val cargoExtension = this
            // It's safe to unwrap, since we bailed at configuration time if this is unset.
            val module = module!!
            val libname = libname!!

            val toolchain by toolchain
            val ndk by ndk

            // run cargo build
            execOperations.exec { spec -> spec.apply {
                standardOutput = System.out
                workingDir = absolutePath(module, project)
                commandLine = buildCommandLine(project, toolchain, cargoExtension)
                environment(getPassthroughEnvironment(project, toolchain))
                environment(getToolchainEnvironment(project, toolchain, ndk, cargoExtension))

                exec?.let {
                    it(spec, toolchain)
                }
            }}.assertNormalExitValue()

            // then copy cargo output to right place
            val fromDir = getCargoOutputDir(project, toolchain, cargoExtension)
            val intoDir by outputDir
            copy { spec ->
                spec.from(fromDir)
                spec.into(intoDir)

                val defaultTargetIncludes = arrayOf(
                    "lib$libname.so",
                    "lib$libname.dylib",
                    "$libname.dll"
                )
                (targetIncludes ?: defaultTargetIncludes).apply {
                    spec.include(asIterable())
                }
            }
        }
    }

    private fun buildCommandLine(
        project: Project, toolchain: Toolchain, cargoExtension: CargoExtension
    ): List<String> = with(cargoExtension) {
        val defaultTargetTriple = getDefaultTargetTriple(project, execOperations, rustcCommand)

        val theCommandLine = mutableListOf(cargoCommand)
        if (rustupChannel.isNotEmpty()) {
            val channel = rustupChannel.trimStart('+')
            theCommandLine.add("+$channel")
        }

        theCommandLine.add("build")

        // Respect `verbose` if it is set; otherwise, log if asked to
        // with `--info` or `--debug` from the command line.
        if (verbose ?: project.logger.isEnabled(LogLevel.INFO)) {
            theCommandLine.add("--verbose")
        }

        if (profile != "debug") {
            // Cargo is rigid: it accepts "--release" for release (and
            // nothing for dev).  This is a cheap way of allowing only
            // two values.
            theCommandLine.add("--${profile}")
        }
        if (toolchain.target != defaultTargetTriple) {
            // Only providing --target for the non-default targets means desktop builds
            // can share the build cache with `cargo build`/`cargo test`/etc invocations,
            // instead of requiring a large amount of redundant work.
            theCommandLine.add("--target=${toolchain.target}")
        }

        // We just pass this along to cargo as something space separated... AFAICT
        // you're allowed to have featureSpec with spaces in them, but I don't think
        // there's a way to specify them in the cargo command line -- rustc accepts
        // them if passed in directly with `--cfg`, and cargo will pass them to rustc
        // if you use them as default featureSpec.
        when (val features = featureSpec.features) {
            is Features.All -> {
                theCommandLine.add("--all-features")
            }
            is Features.DefaultAnd -> {
                if (features.featureSet.isNotEmpty()) {
                    theCommandLine.add("--features")
                    theCommandLine.add(features.featureSet.joinToString(" "))
                }
            }
            is Features.NoDefaultBut -> {
                theCommandLine.add("--no-default-features")
                if (features.featureSet.isNotEmpty()) {
                    theCommandLine.add("--features")
                    theCommandLine.add(features.featureSet.joinToString(" "))
                }
            }
            null -> {}
        }

        extraCargoBuildArguments?.let {
            theCommandLine.addAll(it)
        }

        theCommandLine
    }

    private fun getPassthroughEnvironment(project: Project, toolchain: Toolchain): Map<String, String> {
        // Target-specific environment configuration, passed through to
        // the underlying `cargo build` invocation.
        val toolchainTarget = toolchain.target.uppercase().replace('-', '_')
        val prefix = "RUST_ANDROID_GRADLE_TARGET_${toolchainTarget}_"

        // For ORG_GRADLE_PROJECT_RUST_ANDROID_GRADLE_TARGET_x_KEY=VALUE, set KEY=VALUE.
        project.logger.info("Passing through project properties with prefix '${prefix}' (environment variables with prefix 'ORG_GRADLE_PROJECT_${prefix}'")
        return project.properties
            .filter { (key) -> key.startsWith(prefix) }
            .map { (key, value) ->
                val realKey = key.substringAfter(prefix)
                project.logger.debug(
                    "Passing through environment variable '{}' as '{}={}'",
                    key,
                    realKey,
                    value
                )
                realKey to "$value"
            }.toMap()
    }

    private fun getToolchainEnvironment(
        project: Project, toolchain: Toolchain, ndk: Ndk, cargoExtension: CargoExtension
    ): Map<String, String> = with(cargoExtension) {
        if (toolchain.type == ToolchainType.DESKTOP) {
            return mapOf()
        }

        // Target-specific environment configuration, passed through to
        // the underlying `cargo build` invocation.
        val env = mutableMapOf<String, String>()

        val target = toolchain.target.uppercase().replace('-', '_')
        val apiLevel = apiLevels[toolchain.platform]!!

        val buildDir by project.rootBuildDirectory()
        val linkerWrapper =
            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                File(buildDir, "linker-wrapper/linker-wrapper.bat")
            } else {
                File(buildDir, "linker-wrapper/linker-wrapper.sh")
            }
        env["CARGO_TARGET_${target}_LINKER"] = linkerWrapper.path

        val toolchainDir = if (toolchain.type == ToolchainType.ANDROID_PREBUILT) {
            env["CARGO_NDK_MAJOR_VERSION"] = "${ndk.versionMajor}"

            File("${ndk.path}/toolchains/llvm/prebuilt", getHostTag())
        } else {
            toolchainDirectory
        }
        val cc = File(toolchainDir, "${toolchain.cc(apiLevel)}").path
        val cxx = File(toolchainDir, "${toolchain.cxx(apiLevel)}").path
        val ar = File(toolchainDir, "${toolchain.ar(apiLevel, ndk.versionMajor)}").path

        // For build.rs in `cc` consumers: like "CC_i686-linux-android".  See
        // https://github.com/alexcrichton/cc-rs#external-configuration-via-environment-variables.
        env["CC_${toolchain.target}"] = cc
        env["CXX_${toolchain.target}"] = cxx
        env["AR_${toolchain.target}"] = ar
        if (autoConfigureClangSys) {
            env["CLANG_PATH"] = cc
        }

        // Configure our linker wrapper.
        env["RUST_ANDROID_GRADLE_PYTHON_COMMAND"] = pythonCommand
        env["RUST_ANDROID_GRADLE_LINKER_WRAPPER_PY"] = File(buildDir, "linker-wrapper/linker-wrapper.py").path
        env["RUST_ANDROID_GRADLE_CC"] = cc
        env["RUST_ANDROID_GRADLE_CC_LINK_ARG"] = if (generateBuildId) {
            "-Wl,--build-id,-soname,lib${libname!!}.so"
        } else {
            "-Wl,-soname,lib${libname!!}.so"
        }

        return env
    }

    private fun getCargoOutputDir(project: Project, toolchain: Toolchain, cargoExtension: CargoExtension): File = with(cargoExtension) {
        val target = cargoTargetDir.path
        val defaultTargetTriple = getDefaultTargetTriple(project, execOperations, rustcCommand)

        val targetOutputDir = File(if (toolchain.target == defaultTargetTriple) {
            "${target}/${profile}"
        } else {
            "${target}/${toolchain.target}/${profile}"
        })
        return absolutePath(targetOutputDir, project)
    }

    private fun absolutePath(file: File, project: Project): File {
        val absFile = if (file.isAbsolute) {
            file
        } else {
            File(project.projectDir, file.path)
        }
        return absFile.canonicalFile
    }

    private fun absolutePath(pathname: String, project: Project): File =
        absolutePath(File(pathname), project)

    private fun getHostTag(): String =
        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            if (Os.isArch("x86_64") || Os.isArch("amd64")) {
                "windows-x86_64"
            } else {
                "windows"
            }
        } else if (Os.isFamily(Os.FAMILY_MAC)) {
            "darwin-x86_64"
        } else {
            "linux-x86_64"
        }
}

// This can't be private/internal as it's called from `buildProjectForTarget`.
fun getDefaultTargetTriple(project: Project, execOperations: ExecOperations, rustc: String): String? {
    val stdout = ByteArrayOutputStream()
    val result = execOperations.exec { it.apply {
        standardOutput = stdout
        commandLine = listOf(rustc, "--version", "--verbose")
    }}
    if (result.exitValue != 0) {
        project.logger.warn(
            "Failed to get default target triple from rustc (exit code: ${result.exitValue})")
        return null
    }

    // The `rustc --version --verbose` output contains a number of lines like `key: value`.
    // We're only interested in `host: `, which corresponds to the default target triple.
    val triplePrefix = "host: "

    val triple = stdout.toString().lines()
        .find { it.startsWith(triplePrefix) }
        ?.substringAfter(triplePrefix)
        ?.trim()

    if (triple == null) {
        project.logger.warn("Failed to parse `rustc -Vv` output! (Please report a rust-android-gradle bug)")
    } else {
        project.logger.info("Default rust target triple: $triple")
    }
    return triple
}
