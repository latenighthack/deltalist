plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    js(IR) {
        browser()
    }

    sourceSets {
        jsMain.dependencies {
            api(project(":deltalist-core"))
            api(libs.kotlinx.coroutines.core)
            implementation(project.dependencies.platform(libs.kotlin.wrappers.bom))
            implementation(libs.kotlin.react)
            implementation(libs.kotlin.react.dom)
        }
    }
}
