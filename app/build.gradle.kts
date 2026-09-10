plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.pi.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pi.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.3.0-m3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // 刻意不设 ndk.abiFilters：jniLibs 里有什么就打什么。
        // 想给 APK 瘦身就在 tools/fetch_assets.py --abis 里只取 arm64-v8a。
    }

    // 签名：仅当 CI 注入了密钥环境变量时才启用。
    // 本地没配也能编出 release（只是未签名，需用 adb 装），不会卡住日常构建。
    signingConfigs {
        // CI 注入正式密钥时使用（secrets：SIGNING_KEYSTORE_BASE64 等）。
        if (System.getenv("SIGNING_KEYSTORE") != null) {
            create("release") {
                storeFile = file(System.getenv("SIGNING_KEYSTORE")!!)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
        // 仓库内固定密钥：nightly 侧载包专用。密钥不变，APK 才能覆盖升级
        // ——CI runner 上 AGP 现生成的 debug 密钥每次构建都不同，没法用。
        create("nightly") {
            storeFile = rootProject.file("keystore/nightly.p12")
            storeType = "PKCS12"
            storePassword = "piagent-nightly"
            keyAlias = "piagent"
            keyPassword = "piagent-nightly"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"   // 可与正式包共存
            isMinifyEnabled = false
            // debug 包刻意保留调试能力：可被附加调试器、日志全开
        }
        release {
            // 发布版必须开启混淆 + 资源收缩：剔除 Log.d/v 调试日志、
            // 未用代码，以及被 BuildConfig.DEBUG 挡住的调试页，显著瘦身。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // 签名优先级：CI 正式密钥 > 仓库内 nightly 固定密钥。
            // 未签名 APK 在设备上装不上（报「软件包似乎无效」），必须兜底。
            signingConfig = if (System.getenv("SIGNING_KEYSTORE") != null)
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("nightly")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // AGP 8 起默认不生成 BuildConfig；调试页门控（BuildConfig.DEBUG）依赖它。
        buildConfig = true
    }

    // onnx 模型文件保持不压缩：读得快点，也避免个别 ROM 上解压路径出岔子。
    // （txt/wav 之类不在此列，照旧压缩。）
    androidResources {
        noCompress += listOf("onnx")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // jniLibs 走默认 source set（app/src/main/jniLibs/<abi>/*.so），
    // 内容由 tools/fetch_assets.py --components native 负责拉取。
    // 没有 so 时编译照样通过，只是端侧 VAD / 唤醒会在运行时明确报「不可用」。
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.security.crypto)

    implementation(libs.markwon.core)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.linkify)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
