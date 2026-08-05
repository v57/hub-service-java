plugins {
  `java-library`
  application
}

group = "v57.hubservice"
version = "0.1.0"

repositories {
  mavenCentral()
}

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(21))
  }
}

dependencies {
  implementation("v57.channel:channel-java-core")
  implementation("v57.channel:channel-java-client")

  testImplementation(platform("org.junit:junit-bom:5.10.2"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
  mainClass.set("v57.hubservice.example.EchoMain")
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  testLogging {
    events("failed", "skipped")
  }
}
