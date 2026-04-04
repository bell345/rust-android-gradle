package com.nishtahir

import com.android.build.api.variant.AndroidComponentsExtension
import java.io.File

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

abstract class GenerateToolchainsTask : DefaultTask() {

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    @Suppress("unused")
    fun generateToolchainTask() {
        val cargoExtension = extensions[CargoExtension::class]
        val androidComponents = extensions[AndroidComponentsExtension::class]
        val ndkPath by androidComponents.sdkComponents.ndkDirectory

        with(cargoExtension) {
            // It's safe to unwrap, since we bailed at configuration time if this is unset.
            val targets = targets!!.toSet()
            val platforms = toolchains
                .filter { it.type == ToolchainType.ANDROID_GENERATED }
                .map { it.platform }
                .intersect(targets)
            platforms.forEach { arch ->
                // We ensure all architectures have an API level at configuration time
                val apiLevel = cargoExtension.apiLevels[arch]!!

                // Always regenerate the toolchain, even if it exists
                // already. It is fast to do so and fixes any issues
                // with partially reclaimed temporary files.
                val dir = File(toolchainDirectory, "$arch-$apiLevel")
                execOperations.exec { it.apply {
                    standardOutput = System.out
                    errorOutput = System.out
                    commandLine(pythonCommand)
                    args(
                        "$ndkPath/build/tools/make_standalone_toolchain.py",
                        "--arch=$arch",
                        "--api=$apiLevel",
                        "--install-dir=$dir",
                        "--force")
                }}
            }
        }
    }
}
