plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// 版本号跟 git 提交数走：每推送一次自动 +1，手机端据此判断有没有新版本（应用自更新 M7）
val commitCount: Int = runCatching {
    providers.exec {
        workingDir(rootDir)
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)

// 签名固定用仓库内这把钥匙（本机调试钥匙的副本）：手机上已装的包与云端构建的包
// 签名一致，覆盖安装不需要卸载（个人应用 + 私有仓库，风险可控，M7）
val appKeystore = rootProject.file("keystore/app.keystore")

android {
    namespace = "com.studyfriend.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.studyfriend.app"
        minSdk = 26
        targetSdk = 35
        versionCode = commitCount
        versionName = "0.1.$commitCount"
    }

    signingConfigs {
        if (appKeystore.exists()) {
            create("app") {
                storeFile = appKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("app")
        }
        debug {
            // debug 也锁同一把钥匙：换电脑/CI 构建的调试包签名不变，仍可互相覆盖安装
            signingConfig = signingConfigs.findByName("app")
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
        // 应用自更新（M7）：设置页读 BuildConfig.VERSION_CODE/NAME 做版本比较
        buildConfig = true
    }
    sourceSets {
        // schema JSON 挂 debug 源集：Robolectric 单测读 debug merged assets，
        // MigrationTestHelper 才能拿到 1.json/2.json；release 包不含 schema
        getByName("debug") {
            assets.srcDir("$projectDir/schemas")
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // 低内存机器：限制测试 JVM 堆，避免与 Gradle 同跑时把系统内存挤爆
                test.maxHeapSize = "512m"
                // Robolectric 的 android-all 依赖默认直连 repo1.maven.org，
                // 该 key 只认 JVM 系统属性（robolectric.properties 里写无效）
                test.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
                test.systemProperty("robolectric.dependency.repo.id", "aliyun")
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    // 视觉后台队列（OPT-F）：系统托管的持久化后台任务，进程被杀/设备重启自动续跑
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // PDF 文本提取（pdfbox 2.0 的 Android 移植）
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    // Compose UI 冒烟测试（M4a：章目录/阅读页渲染）；manifest 须随 debug 变体合并
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
