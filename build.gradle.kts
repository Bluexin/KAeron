import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
}

group = "be.bluexin"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(coroutine("jdk8"))

    api(aeron("client"))
    implementation(aeron("cluster"))
    implementation(aeron("driver"))
    implementation(aeron("agent"))
    implementation("uk.co.real-logic", "sbe-tool", version("sbe"))

    testImplementation("com.github.javafaker", "javafaker", version("javafaker"))
    implementation("com.fasterxml.jackson.module", "jackson-module-kotlin", version("jackson"))

    // Logging
    implementation("org.slf4j", "slf4j-api", version("slf4j"))
    implementation("io.github.microutils", "kotlin-logging", version("klog"))
    runtimeOnly("ch.qos.logback", "logback-classic", version("logback"))
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        @Suppress("SuspiciousCollectionReassignment")
        freeCompilerArgs += listOf("-Xuse-experimental=kotlin.Experimental", "-XXLanguage:+InlineClasses")
    }
}

tasks.withType<AbstractArchiveTask> {
    archiveBaseName.convention(provider { project.name.toLowerCase() })
}

fun Project.version(name: String) = extra.properties["${name}_version"] as? String

fun Project.coroutine(module: String): Any =
    "org.jetbrains.kotlinx:kotlinx-coroutines-$module:${version("coroutines")}"

fun Project.aeron(module: String): Any =
    "io.aeron:aeron-$module:${version("aeron")}"
