// The daemon must run on JDK 25 (gradle/gradle-daemon-jvm.properties): Spotless parses with the daemon's javac.
check(JavaVersion.current().majorVersion.toInt() >= 25) {
    "Gradle is running on Java ${JavaVersion.current()}; this build needs JDK 25 for the Gradle daemon. " +
        "Install JDK 25 and, in IntelliJ, set Settings > Build Tools > Gradle > Gradle JVM to it (or leave it on " +
        "'Project SDK' with a JDK 25 project SDK). From a shell, point JAVA_HOME at JDK 25."
}

rootProject.name = "northline-server"
include("platform", "email", "sms", "search-index", "openapi", "api", "auth", "bff", "worker", "event-contracts")
