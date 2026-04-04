plugins {
    alias(libs.plugins.gradle.versions)
    alias(libs.plugins.version.catalog.update)
}

tasks {
    register<Delete>("clean") {
        delete(rootProject.layout.buildDirectory)
    }
}
