rootProject.name = "hub-service-java"

// The channel-java library (channel-java-core + channel-java-client) is a
// sibling repo consumed as an included (composite) build — same wiring the
// channel-kotlin port uses.
includeBuild("../channel-java")
