plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "edu.colorado.rrassist.cli"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":plugin"))
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("edu.colorado.rrassist.cli.CliMainKt")
}
