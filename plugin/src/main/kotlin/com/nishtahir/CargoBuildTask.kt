package com.nishtahir

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory

abstract class CargoBuildTask : DefaultTask() {
    @get:OutputDirectory
    abstract val jniOutputDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val testOutputDirectory: DirectoryProperty

    init {
        jniOutputDirectory.set(project.layout.buildDirectory.dir("rustJniLibs/android"))
        testOutputDirectory.set(project.layout.buildDirectory.dir("rustJniLibs/desktop"))
    }
}