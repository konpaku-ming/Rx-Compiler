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
    jvmToolchain(21)
}

application {
    // 指定包含 main 函数的主类
    mainClass.set("MainKt") // 注意：如果你的文件名是 Compiler.kt，主类名就是 CompilerKt
}
