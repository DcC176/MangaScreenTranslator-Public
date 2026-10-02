// 注意：在 .gradle.kts 里不能写 `java.util.Properties`，
// 因为脚本作用域中的 `java` 会被解析成 JavaPluginExtension 而不是包名，必须显式 import。
import java.util.Properties
import com.android.build.api.variant.FilterConfiguration
import com.android.build.api.variant.impl.VariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release 签名。密钥与密码放在根目录的 keystore.properties（已 gitignore），
// 不写死在构建脚本里，避免误提交。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.mangatranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mangatranslate"
        minSdk = 26
        targetSdk = 35
        versionCode = 44
        versionName = "1.5.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 压缩。ML Kit / OkHttp / Compose 都自带 consumer proguard 规则，
            // 加上 app/proguard-rules.pro 里的补充规则，实测构建通过。
            isMinifyEnabled = true

            // 不开资源裁剪：它只能省不到 1MB（APK 里 res 才 0.2MB、resources.arsc 0.7MB，
            // 真正占体积的是 ML Kit 的原生库），却会多写一个 mapping 文件，
            // 在受限目录下容易构建失败。性价比不划算。
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // 两个分发形态。分开的唯一硬理由是合规：ML Kit 的离线翻译模型属于 Google 专有条款下的
    // "related software"，未授予再分发权利，因此**对外发布的包里不能内置**。
    //
    //   foss —— 发布版。不含模型，首次使用时走 ML Kit 官方下载通道。
    //           同时关闭 Pro 门禁：发布形态是「完整免费 + 捐赠」，不锁任何功能。
    //   full —— 自用版。内置模型、离线即用、保留 Pro 授权链，但不对外分发。
    //
    // 本仓库（Public）只含文档与构建配置，不含源码与模型资产，
    // 因此按设计不可构建 —— 这里的变体定义是为了与主仓库保持一致。
    flavorDimensions += "distribution"

    productFlavors {
        create("foss") {
            dimension = "distribution"
            buildConfigField("boolean", "BUNDLED_MODELS", "false")
            buildConfigField("boolean", "PRO_ENABLED", "false")
        }
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "BUNDLED_MODELS", "true")
            buildConfigField("boolean", "PRO_ENABLED", "true")
            // 两个变体若共用 applicationId，安装时会互相静默替换 —— 装错版本看不出来。
            applicationIdSuffix = ".full"
            versionNameSuffix = "-selfuse"
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
        // 主页要显示版本号，用 BuildConfig.VERSION_NAME 免得手写常量与构建配置脱节
        buildConfig = true
    }

    // ML Kit 的 OCR / 翻译原生库每套 ABI 就有 25~50MB。
    // 不拆的话 universal 包要 113MB，其中 90% 是用户手机用不到的其它架构。
    // 拆完之后：arm64-v8a 约 50MB（覆盖近五年的所有真机），另附一个 universal 备用。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*"
        )
    }

    androidResources {
        // 内置的离线翻译模型是 zip（en_ja / en_zh，合计 83MB，位于 full 变体的
        // src/full/assets/models/）。它们本身已经是压缩格式，让 aapt 再压一遍既省不下
        // 体积，又会让 AssetManager.openFd() 失效（被压缩过的 asset 拿不到文件描述符）。
        // 直接原样存进 APK。
        noCompress += listOf("zip")
    }
}

// 产物文件名：保持历史命名。对外发布的 foss 包沿用 app-<abi>-<type>.apk，
// 自用版加 `full-` 标记以便区分。
//
// `outputFileName` 只存在于内部实现类 VariantOutputImpl，公开接口 VariantOutput
// （AGP 8.5.2）没有暴露它。这里用硬转换：AGP 升级若改了类型，应当在配置阶段就报错，
// 而不是静默地不重命名。
androidComponents {
    onVariants { variant ->
        val prefix = if (variant.flavorName == "full") "full-" else ""
        variant.outputs.forEach { output ->
            val o = output as VariantOutputImpl
            val abi = o.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier ?: "universal"
            o.outputFileName.set("app-$prefix$abi-${variant.buildType}.apk")
        }
    }
}

dependencies {
    // ---- Compose ----
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")

    // ---- OCR：ML Kit 日文识别（含竖排支持）----
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")

    // ---- 离线翻译：ML Kit 端上翻译（ja -> zh，完全离线免费）----
    implementation("com.google.mlkit:translate:17.0.3")

    // ---- 在线引擎（DeepL / Google / OpenAI 兼容 LLM）----
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ---- 协程 ----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
