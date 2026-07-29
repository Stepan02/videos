plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.allopen") version "2.3.21"
    id("com.google.devtools.ksp") version "2.3.7"
    id("io.micronaut.application") version "5.0.2"
    id("com.gradleup.shadow") version "9.4.1"
    id("io.micronaut.test-resources") version "5.0.2"
    id("io.micronaut.aot") version "5.0.2"
}

version = "0.1"
group = "cloud.videos"

val kotlinVersion = project.properties.get("kotlinVersion")

repositories {
    mavenCentral()
}

dependencies {
    ksp("io.micronaut.data:micronaut-data-document-processor")
    ksp("io.micronaut:micronaut-http-validation")
    ksp("io.micronaut.serde:micronaut-serde-processor")

    implementation("org.bytedeco:javacv:1.5.10")
    implementation("org.bytedeco:ffmpeg-platform:6.1.1-1.5.10")
    implementation("org.mongodb:mongodb-driver-sync")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk9")
    implementation("io.micronaut:micronaut-websocket")
    implementation("io.micronaut.kotlin:micronaut-kotlin-extension-functions")
    implementation("io.micronaut.kotlin:micronaut-kotlin-runtime")
    implementation("io.micronaut.data:micronaut-data-mongodb")
    implementation("io.micronaut.redis:micronaut-redis-lettuce")
    implementation("io.micronaut.redis:micronaut-redis-lettuce-pubsub")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
}

application {
    mainClass = "cloud.videos.ApplicationKt"
}

java {
    sourceCompatibility = JavaVersion.toVersion("25")
}

graalvmNative.toolchainDetection = false
graalvmNative {
    binaries {
        all {
            buildArgs.add("-H:+SharedArenaSupport")
        }
    }
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        module(project.name)
        annotations("cloud.videos.*")
    }

    testResources {
        version = "4.0.0"
    }

    aot {
        // Please review carefully the optimizations enabled below
        // Check https://micronaut-projects.github.io/micronaut-aot/latest/guide/ for more details
        optimizeServiceLoading = false
        convertYamlToJava = false
        precomputeOperations = true
        cacheEnvironment = true
        optimizeClassLoading = true
        deduceEnvironment = true
        optimizeNetty = true
        replaceLogbackXml = true
    }
}

tasks.withType<io.micronaut.gradle.testresources.StartTestResourcesService>().configureEach {
    useClassDataSharing.set(false)
}

tasks.named<io.micronaut.gradle.docker.MicronautDockerfile>("dockerfile") {

    baseImage = "eclipse-temurin:25-jre"
}

// https://docs.gradle.org/current/userguide/upgrading_major_version_9.html#test_task_fails_when_no_tests_are_discovered
tasks.withType<AbstractTestTask>().configureEach {
    failOnNoDiscoveredTests = false
}
