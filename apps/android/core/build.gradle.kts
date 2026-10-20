plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    systemProperty("protocolFixtures", rootProject.file("../../testdata/vpn-protocol.json").absolutePath)
    testLogging {
        events("failed", "skipped")
    }
}
