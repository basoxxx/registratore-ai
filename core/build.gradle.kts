plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test>().configureEach {
    // Test d'integrazione opzionale con la libreria nativa compilata per l'host
    System.getenv("WHISPER_JNI_DIR")?.let { systemProperty("java.library.path", it) }
    testLogging { showStandardStreams = true }
}
