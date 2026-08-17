plugins {
    id("com.android.application")
}

android {
    namespace = "com.steamlike.controller"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.steamlike.controller"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 单元测试依赖
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// ====================================================================
// Windows 客户端预编译任务
// ====================================================================
// 在 APK 编译前自动编译 Windows 端 inputbridge_client.exe 并打包到 assets 目录。
// 这样 APK 内置的 exe 始终与源码同步，无需手动编译。
//
// 依赖: MinGW gcc (M:\msys64\ucrt64\bin\gcc.exe 或系统 PATH 中的 gcc)
// 如 gcc 不可用，任务会跳过（使用 assets 中已有的 exe）
// ====================================================================

tasks.register("compileWindowsExe") {
    group = "build"
    description = "Compiles Windows inputbridge_client.exe and copies to assets"

    val sourceFile = rootProject.file("windows/inputbridge_client.c")
    val outputExe = rootProject.file("windows/inputbridge_client.exe")
    val assetsExe = file("src/main/assets/inputbridge_client.exe")

    // 搜索 gcc 路径
    val gccCandidates = listOf(
        "M:/msys64/ucrt64/bin/gcc.exe",
        "C:/msys64/ucrt64/bin/gcc.exe",
        "C:/MinGW/bin/gcc.exe"
    )
    val systemPath = System.getenv("PATH") ?: ""
    val pathGcc = systemPath.split(File.pathSeparator)
        .map { File(it, "gcc.exe") }
        .firstOrNull { it.exists() }
    val gccPath = gccCandidates.map { File(it) }.firstOrNull { it.exists() } ?: pathGcc

    doLast {
        if (gccPath == null || !gccPath.exists()) {
            logger.warn("gcc not found, skipping Windows exe compilation. Using existing assets/inputbridge_client.exe")
            return@doLast
        }
        logger.lifecycle("Compiling Windows exe with: ${gccPath.absolutePath}")

        // 确保输出目录存在
        outputExe.parentFile.mkdirs()
        assetsExe.parentFile.mkdirs()

        // 执行编译命令
        val cmd = listOf(
            gccPath.absolutePath,
            "-O2",
            "-o", outputExe.absolutePath,
            sourceFile.absolutePath,
            "-lws2_32", "-luser32"
        )
        val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.warn("gcc compilation failed (exit=$exitCode), using existing exe. Output:\n$output")
            return@doLast
        }
        logger.lifecycle("Windows exe compiled: ${outputExe.absolutePath} (${outputExe.length()} bytes)")

        // 复制到 assets
        outputExe.copyTo(assetsExe, overwrite = true)
        logger.lifecycle("Copied to assets: ${assetsExe.absolutePath}")
    }
}

// 在 preBuild 前执行编译
tasks.named("preBuild") {
    dependsOn("compileWindowsExe")
}
