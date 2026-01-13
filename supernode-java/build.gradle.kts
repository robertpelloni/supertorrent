plugins {
    java
    `java-library`
}

group = "io.supernode"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    // Cryptography
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    
    // WebSocket / Networking (Netty)
    implementation("io.netty:netty-all:4.1.115.Final")
    
    // ISO 9660 creation
    implementation("com.github.stephenc.java-iso-tools:iso9660-writer:2.0.1")
    implementation("com.github.stephenc.java-iso-tools:sabre:2.0.1")
    
    // Blockchain (Web3j for EVM/Bobcoin)
    implementation("org.web3j:core:4.12.2")
    
    // JSON processing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.0")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.8")
    
    // Testing
    testImplementation(platform("org.junit:junit-bom:5.11.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core:5.14.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.0")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:unchecked", "-Xlint:deprecation"))
}
