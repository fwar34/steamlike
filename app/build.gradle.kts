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
        versionName = "0.1.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // 生成 BuildConfig（用于在界面显示 versionName）
    buildFeatures {
        buildConfig = true
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
// 在 APK 编译前自动执行：
//   1. 编译 windows/inputbridge_client.c → 打包到 assets（gcc 可用时）
//   2. 同步 windows/control.bat → 打包到 assets（脚本始终与源码一致）
// 这样 APK 内置的 exe 和脚本每次都与源码同步，无需手动复制。
//
// 依赖: MinGW gcc (M:\msys64\ucrt64\bin\gcc.exe 或系统 PATH 中的 gcc)
// 如 gcc 不可用，exe 编译跳过（使用 assets 中已有的 exe），脚本仍会同步。
// ====================================================================

tasks.register("compileWindowsExe") {
    group = "build"
    description = "Compiles Windows inputbridge_client.exe and syncs scripts to assets"

    val sourceFile = rootProject.file("windows/inputbridge_client.c")
    val outputExe = rootProject.file("windows/inputbridge_client.exe")
    val assetsExe = file("src/main/assets/inputbridge_client.exe")
    val controlSource = rootProject.file("windows/control.bat")
        val assetsControl = file("src/main/assets/control.bat")
        val controlPs1Source = rootProject.file("windows/control.ps1")
        val assetsPs1Control = file("src/main/assets/control.ps1")

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
        // 1. 编译 exe（gcc 可用时）
        if (gccPath == null || !gccPath.exists()) {
            logger.warn("gcc not found, skipping Windows exe compilation. Using existing assets/inputbridge_client.exe")
        } else {
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
            } else {
                logger.lifecycle("Windows exe compiled: ${outputExe.absolutePath} (${outputExe.length()} bytes)")

                // 复制到 assets
                outputExe.copyTo(assetsExe, overwrite = true)
                logger.lifecycle("Copied to assets: ${assetsExe.absolutePath}")
            }
        }

        // 2. 始终同步 control.bat 脚本到 assets（保证 APK 内脚本与源码一致）
        if (controlSource.exists()) {
            assetsControl.parentFile.mkdirs()
            controlSource.copyTo(assetsControl, overwrite = true)
            logger.lifecycle("Copied control.bat to assets: ${assetsControl.absolutePath}")
        } else {
            logger.warn("windows/control.bat not found, keeping existing assets/control.bat")
        }

        // 3. 同步 control.ps1 脚本到 assets（保证 APK 内脚本与源码一致）
        if (controlPs1Source.exists()) {
            assetsPs1Control.parentFile.mkdirs()
            controlPs1Source.copyTo(assetsPs1Control, overwrite = true)
            logger.lifecycle("Copied control.ps1 to assets: ${assetsPs1Control.absolutePath}")
        } else {
            logger.warn("windows/control.ps1 not found, keeping existing assets/control.ps1")
        }
    }
}

// 在 preBuild 前执行编译
tasks.named("preBuild") {
    dependsOn("compileWindowsExe")
}
