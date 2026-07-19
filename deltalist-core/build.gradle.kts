import co.touchlab.skie.configuration.EnumInterop
import co.touchlab.skie.configuration.FlowInterop
import co.touchlab.skie.configuration.SealedInterop
import co.touchlab.skie.configuration.SuspendInterop
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.skie)
    alias(libs.plugins.maven.publish)
}

skie {
    features {
        group {
            FlowInterop.Enabled(false)
            SealedInterop.Enabled(false)
            EnumInterop.Enabled(false)
            SuspendInterop.Enabled(false)
        }
    }
}

kotlin {
    jvm()

    // Android consumers (e.g. basekit's KMP modules, which all include an androidTarget) reference
    // Delta types from commonMain, so core must publish an android variant alongside jvm/js/ios.
    androidTarget { publishLibraryVariants("release") }

    js(IR) {
        browser()
        // nodejs enables headless execution of commonTest on the JS target in CI.
        nodejs()
    }

    val xcf = XCFramework("DeltaListCore")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = "DeltaListCore"
            isStatic = false
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

android {
    namespace = "com.latenighthack.deltalist"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
