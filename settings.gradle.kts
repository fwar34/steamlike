// ===== 以下为被注释掉的默认仓库配置（已改用下方阿里云镜像加速国内下载）=====
// pluginManagement {
//     repositories {
//         google()
//         mavenCentral()
//         gradlePluginPortal()
//     }
// }

// dependencyResolutionManagement {
//     repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
//     repositories {
//         google()
//         mavenCentral()
//     }
// }
pluginManagement {  // 语法：pluginManagement{} 块，配置 Gradle 插件（构建脚本插件）的解析仓库来源
    repositories {  // 语法：repositories{} 块，声明插件仓库列表（按顺序查找）
        maven("https://maven.aliyun.com/repository/google")  // 语法：maven("URL") 添加 Maven 仓库；阿里云 Google 镜像（加速 Android 插件/依赖下载）
        maven("https://maven.aliyun.com/repository/maven-public")  // 阿里云公共镜像（聚合 Maven 中央仓库等）
        maven("https://maven.aliyun.com/repository/gradle-plugin")  // 阿里云 Gradle 插件镜像（Gradle 插件下载）
        google()  // 语法：google() 官方 Google Maven 仓库（Google 托管的相关依赖）
        mavenCentral()  // 语法：mavenCentral() 官方 Maven 中央仓库
        gradlePluginPortal()  // 语法：gradlePluginPortal() Gradle 官方插件门户（发布 Gradle 插件的仓库）
    }  // 结束 repositories 块
}  // 结束 pluginManagement 块
dependencyResolutionManagement {  // 语法：dependencyResolutionManagement{} 块，管理项目所有依赖的解析策略与仓库
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)  // 语法：repositoriesMode.set(...) 设置仓库模式；FAIL_ON_PROJECT_REPOS 禁止在各模块 build.gradle 中再声明仓库（统一在此配置）
    repositories {  // 语法：repositories{} 块，声明依赖仓库列表
        maven("https://maven.aliyun.com/repository/google")  // 阿里云 Google 镜像仓库
        maven("https://maven.aliyun.com/repository/maven-public")  // 阿里云公共镜像仓库
        google()  // 官方 Google Maven 仓库
        mavenCentral()  // 官方 Maven 中央仓库
    }  // 结束 repositories 块
}  // 结束 dependencyResolutionManagement 块

rootProject.name = "SteamLikeController"  // 语法：rootProject.name 设置根项目名称（显示在 IDE 与构建输出中）
include(":app")  // 语法：include(":模块名") 声明项目包含的子模块（app 模块参与构建）
