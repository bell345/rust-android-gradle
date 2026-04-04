package com.nishtahir

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtensionContainer
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.util.Properties
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

operator fun <T : Any> ExtensionContainer.get(type: KClass<T>): T = getByType(type.java)

inline operator fun<reified T : Any> Provider<T>.getValue(thisRef: Any?, property: KProperty<*>): T = this.get()
inline operator fun<reified T : Any> Property<T>.setValue(thisRef: Any?, property: KProperty<*>, value: T) = this.set(value)

fun Project.rootBuildDirectory(): Provider<File> = rootProject.layout.buildDirectory.asFile
fun Project.buildDirectory(): Provider<File> = layout.buildDirectory.asFile

inline fun<reified T : Any> DefaultTask.property(): Property<T> = project.objects.property(T::class.java)

fun CharSequence.capitalized() = toString().replaceFirstChar { if (it.isLowerCase()) it.uppercase() else it.toString() }

fun Properties.maybeLoadFromFile(parent: File, child: String): Properties {
    val localPropertiesFile = File(parent, child)
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
    return this
}

inline fun<reified T : Task> TaskContainer.register(name: String, crossinline configure: T.() -> Unit) = register(name, T::class.java) {
    it.apply(configure)
}

inline fun <reified T : Task> TaskContainer.maybeRegister(
    name: String,
    noinline configure: T.() -> Unit,
): TaskProvider<T> {
    return if (name in names) {
        named(name, T::class.java)
    } else {
        register<T>(name, configure)
    }
}