import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    `maven-publish`
    kotlin("jvm")
}

group = "be.bluexin"
version = "1.0-SNAPSHOT"

java {
    withSourcesJar()
}

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

    // Testing
    testImplementation("org.junit.jupiter", "junit-jupiter-api", version("junit"))
    testImplementation("org.junit.jupiter", "junit-jupiter-params", version("junit"))
    testRuntimeOnly("org.junit.jupiter", "junit-jupiter-engine", version("junit"))
}

tasks.test {
    useJUnitPlatform()
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

publishing {
    publications.create<MavenPublication>("publication") {
        from(components["java"])
        this.artifactId = base.archivesBaseName
    }

    repositories {
        val mavenPassword = if (hasProp("local")) null else prop("sbxMavenPassword")
        maven {
            url = uri(if (mavenPassword != null) "sftp://maven.sandboxpowered.org:22/sbxmvn/" else "file://$buildDir/repo")
            if (mavenPassword != null) {
                credentials(PasswordCredentials::class.java) {
                    username = prop("sbxMavenUser")
                    password = mavenPassword
                }
            }
        }

    }
}

fun hasProp(name: String): Boolean = extra.has(name)

fun prop(name: String): String? = extra.properties[name] as? String

fun Project.version(name: String) = extra.properties["${name}_version"] as? String

fun Project.coroutine(module: String): Any =
    "org.jetbrains.kotlinx:kotlinx-coroutines-$module:${version("coroutines")}"

fun Project.aeron(module: String): Any =
    "io.aeron:aeron-$module:${version("aeron")}"
