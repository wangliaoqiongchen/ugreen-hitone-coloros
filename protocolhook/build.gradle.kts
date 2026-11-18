plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mo.ugreenprotocolhook"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.mo.ugreenprotocolhook"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    /*
     * Xposed / LSPosed 的 legacy API。
     *
     * compileOnly：只参与编译，不打进 APK。
     * 运行期由 LSPosed 框架把自己那份 API 注入模块的 ClassLoader。
     *
     * 这份 jar 来自阿里云 public 仓库：
     *   de.robv.android.xposed:api:82
     * 放在 protocolhook/libs/api-82.jar
     */
    compileOnly(files("libs/api-82.jar"))

    // 模块自带的日志查看界面
    implementation(libs.androidx.appcompat)
}
