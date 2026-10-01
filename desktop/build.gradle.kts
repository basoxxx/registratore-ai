import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val buildNumber = System.getenv("BUILD_NUMBER") ?: "1"

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("org.json:json:20240303")
}

compose.desktop {
    application {
        mainClass = "it.registratoreai.desktop.MainKt"
        jvmArgs += listOf("-Xmx2g", "-Dapp.version=1.0.$buildNumber")

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Registratore Lezioni"
            packageVersion = "1.0.$buildNumber"
            description = "Registra le lezioni e trascrivile offline con Whisper"
            vendor = "Registratore Lezioni"
            copyright = "Open source"
            includeAllModules = true
            // La libreria nativa whisper_jni compilata per ogni sistema va in resources/<os>-<arch>/
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            macOS {
                bundleID = "it.registratoreai.desktop"
                iconFile.set(project.file("icons/icon.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Serve il microfono per registrare le lezioni.</string>
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menu = true
                menuGroup = "Registratore Lezioni"
                shortcut = true
                perUserInstall = true
                dirChooser = false
                upgradeUuid = "5b8f3c1e-2a47-4d6b-9e0f-7c3a1d2b4e60"
            }
            linux {
                packageName = "registratore-lezioni"
                iconFile.set(project.file("icons/icon.png"))
            }
        }
    }
}
