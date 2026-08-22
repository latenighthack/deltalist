import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.skie)
}

skie {
    features {
        // Enable SwiftUI preview support
        enableSwiftUIObservingPreview = true
    }
}

kotlin {
    jvm()

    js(IR) {
        browser()
    }

    val xcf = XCFramework("DemoCore")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
        // macOS is here so the AppKit/SwiftUI Mac demo can link the same shared view models.
        macosArm64(),
        macosX64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = "DemoCore"
            isStatic = true
            xcf.add(this)
            // Export deltalist-core types through this framework
            export(project(":deltalist-core"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":deltalist-core"))
            api(libs.kotlinx.coroutines.core)
        }
        jsMain.dependencies {
            api(project(":deltalist-react"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
