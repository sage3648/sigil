plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(project(":compiler"))
    testImplementation(project(":registry"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test { useJUnitPlatform() }

kotlin { jvmToolchain(23) }

application { mainClass.set("sigil.examples.MainKt") }
