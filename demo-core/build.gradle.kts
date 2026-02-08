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
        iosSimulatorArm64()
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
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
