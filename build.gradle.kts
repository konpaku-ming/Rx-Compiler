import org.gradle.api.tasks.JavaExec

plugins {
    kotlin("jvm") version "2.2.0"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

application {
    // 指定包含 main 函数的主类
    mainClass.set("MainKt") // 注意：如果你的文件名是 Compiler.kt，主类名就是 CompilerKt
}

// Add a convenience task to run the TestRunner (located in src/test/kotlin).
// Usage from Windows cmd.exe:
//   gradlew.bat runTestRunner -PtrArgs="--quiet"
// The task ensures test classes are compiled first and puts both main/test runtime classpaths on the classpath.
tasks.register<JavaExec>("runTestRunner") {
    group = "verification"
    description = "Run the TestRunner (main in src/test/kotlin). Pass args via -PtrArgs=\"...\" (e.g. --quiet)."

    // Ensure test classes are compiled before running
    dependsOn("testClasses")

    // TestRunner.kt defines a top-level `main`, compiled into TestRunnerKt
    mainClass.set("TestRunnerKt")

    // Include both main and test runtime classpaths
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].runtimeClasspath

    // Accept args via -PtrArgs property (split on whitespace)
    args = project.findProperty("trArgs")?.toString()?.split("\\s+".toRegex()) ?: listOf()
}

// Add a convenience task to run the IRTestRunner (located in src/test/kotlin).
// Usage:
//   ./gradlew runIRTestRunner
//   ./gradlew runIRTestRunner -PirArgs="--quiet"
//   ./gradlew runIRTestRunner -PirArgs="--fail-fast"
tasks.register<JavaExec>("runIRTestRunner") {
    group = "verification"
    description = "Run the IRTestRunner (IR-1 end-to-end tests). Pass args via -PirArgs=\"...\" (e.g. --quiet)."

    // Ensure test classes are compiled before running
    dependsOn("testClasses")

    // IRTestRunner.kt defines a top-level `main`, compiled into IRTestRunnerKt
    mainClass.set("IRTestRunnerKt")

    // Include both main and test runtime classpaths
    classpath = sourceSets["test"].runtimeClasspath + sourceSets["main"].runtimeClasspath

    // Accept args via -PirArgs property (split on whitespace)
    args = project.findProperty("irArgs")?.toString()?.split("\\s+".toRegex()) ?: listOf()
}
