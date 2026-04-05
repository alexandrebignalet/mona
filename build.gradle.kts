plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("com.gradleup.shadow") version "8.3.5"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.2"
}

group = "mona"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

val exposedVersion = "0.56.0"
val coroutinesVersion = "1.9.0"

dependencies {
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")

    // Exposed ORM + SQLite
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-java-time:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // PDF generation
    implementation("org.apache.pdfbox:pdfbox:3.0.3")

    // JSON (for Claude API, Resend, SIRENE)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("")
    manifest {
        attributes("Main-Class" to "mona.AppKt")
    }
    mergeServiceFiles()
}
