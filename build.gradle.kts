import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
    id("com.gradleup.shadow") version "8.3.11"
}

group = "com.flamerealms"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")

    // Vault's API is published only through JitPack (net.milkbowl.vault /
    // com.github.MilkBowl:VaultAPI is not on Maven Central) — see the
    // compileOnly dependency below.
    maven("https://jitpack.io")
}

dependencies {
    // Paper API — provided by the server at runtime, never shaded.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // Vault's economy API, for the OPTIONAL VaultEconomyBridge
    // (com.flamerealms.economy.VaultEconomyBridge). compileOnly: FlameRealms
    // never requires Vault to be installed to run — see
    // FlameRealmsPlugin#onEnable, which registers the bridge only if the
    // Vault plugin is actually present at runtime, and plugin.yml's
    // softdepend entry for Vault. Resolved via JitPack as
    // com.github.MilkBowl:VaultAPI, since Vault does not publish to Maven
    // Central; 1.7.1 is VaultAPI's latest tagged release as of writing.
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1")

    // Shaded into the plugin jar (see shadowJar relocations below).
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.flywaydb:flyway-core:10.20.1")
    implementation("org.flywaydb:flyway-mysql:10.20.1")
    implementation("org.mariadb.jdbc:mariadb-java-client:3.4.1")

    // Tests

    // compileOnly is not inherited by the test classpath automatically (only
    // testImplementation extends implementation) — added explicitly so
    // ActivityTrackingServiceTest/UpkeepServiceTest can Mockito.mock(...) a
    // JavaPlugin. testImplementation (not testCompileOnly) because Mockito
    // needs the class on the TEST RUNTIME classpath too, not just at compile
    // time; this never reaches the shaded plugin jar (shadowJar only bundles
    // the main source set's runtime classpath, never test dependencies), so
    // the production compileOnly above is unaffected.
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    // 5.20.0+ bundles a Byte Buddy new enough to instrument classes on the
    // project's Java 25 toolchain — 5.14.x's bundled Byte Buddy cannot.
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Testcontainers — used only by the @Tag("integration") DAO tests (see
    // JdbcRealmDaoIT). Not needed by the fake-DAO RealmServiceImpl unit
    // tests, which run with no Docker/DB dependency at all.
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:mariadb:1.21.4")
}

// Default 'test' task: fast, no external dependencies. Excludes anything
// tagged "integration" (i.e. JdbcRealmDaoIT), so this passes even in a
// sandbox with no Docker daemon.
tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// Testcontainers-backed DAO integration tests. Requires a working Docker
// daemon (or another Testcontainers-compatible container runtime) on the
// machine running it. Run explicitly with: ./gradlew integrationTest
tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers-backed DAO integration tests (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    shouldRunAfter(tasks.test)
}

tasks.shadowJar {
    archiveClassifier.set("")

    relocate("com.zaxxer.hikari", "com.flamerealms.libs.hikari")
    relocate("org.flywaydb", "com.flamerealms.libs.flyway")
    relocate("org.mariadb.jdbc", "com.flamerealms.libs.mariadb")

    // Without this, META-INF/services/java.sql.Driver still lists the
    // pre-relocation class name (org.mariadb.jdbc.Driver), which no longer
    // exists in the shaded jar — DriverManager/ServiceLoader then fails
    // with "No suitable driver" for the mariadb: JDBC URL.
    mergeServiceFiles()
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}
