import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    // 管理台（AdminServer）直接用到 NanoHTTPD 与 Gson；core 里是 implementation 依赖，不会传递到这里
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
}

application {
    // 生成的可执行脚本名（./gradlew :cli:installDist 后为 bin/aigw），与帮助文本、文档中的命令名一致。
    applicationName = "aigw"
    mainClass.set("dev.aigw.cli.MainKt")
}
