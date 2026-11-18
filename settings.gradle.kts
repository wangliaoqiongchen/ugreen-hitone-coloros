pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "UGREEN for ColorOS"

/*
 * :app —— 调试台（绿联耳机调试台）
 *
 * 手动扫描 / 连接 / 订阅 / 发命令 / 看完整日志。
 * 用于调试与协议取证；系统里的降噪控制【不依赖它】。
 */
include(":app")

/*
 * :protocolhook —— LSPosed 模块（绿联耳机增强）
 *
 * 让 T6 接入 ColorOS：
 *   · 音量面板里的降噪控制
 *   · 「设备空间」里的降噪入口
 * 模块自己连接耳机发命令，不需要任何配套 APP。
 *
 * Xposed API 以本地 jar 形式提供（仅参与编译，不打进 APK）：
 *   protocolhook/libs/api-82.jar   (de.robv.android.xposed:api:82)
 */
include(":protocolhook")
