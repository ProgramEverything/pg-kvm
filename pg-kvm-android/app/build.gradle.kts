import java.io.FileInputStream
import java.util.Properties

@Suppress("DSL_SCOPE_VIOLATION")
plugins {
  id(libs.plugins.android.application.get().pluginId)
  id(libs.plugins.kotlin.android.get().pluginId)
  id(libs.plugins.compose.compiler.get().pluginId)
  id("org.jetbrains.kotlin.kapt")
  id("com.yanzhenjie.andserver")
}

val localProperties = Properties()
localProperties.load(FileInputStream(rootProject.file("local.properties")))

// release 签名配置，密码等信息存于 keystore.properties（已被 .gitignore 忽略）
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
  if (keystorePropertiesFile.exists()) keystorePropertiesFile.inputStream().use(::load)
}

android {
  namespace = "io.getstream.webrtc.sample.compose"
  compileSdk = Configurations.compileSdk

  defaultConfig {
    applicationId = "io.getstream.webrtc.sample.compose"
    minSdk = Configurations.minSdk
    targetSdk = Configurations.targetSdk
    versionCode = Configurations.versionCode
    versionName = Configurations.versionName

    buildConfigField(
      "String",
      "SIGNALING_SERVER_IP_ADDRESS",
      localProperties["SIGNALING_SERVER_IP_ADDRESS"].toString()
    )
  }

  signingConfigs {
    create("release") {
      if (keystorePropertiesFile.exists()) {
        storeFile = rootProject.file(keystoreProperties["storeFile"] as String)
        storePassword = keystoreProperties["storePassword"] as String
        keyAlias = keystoreProperties["keyAlias"] as String
        keyPassword = keystoreProperties["keyPassword"] as String
      }
    }
  }

  buildTypes {
    release {
      signingConfig = signingConfigs.getByName("release")
    }
  }

  kotlinOptions {
    jvmTarget = "17"
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  packagingOptions {
    resources {
      excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
  }

  lint {
    abortOnError = false
  }
}

dependencies {
  implementation(project(":libausbc"))
  implementation(project(":libuvc"))

  // compose
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.runtime)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling)
  implementation(libs.androidx.compose.material)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.foundation.layout)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.constraintlayout)

  // image loading
  implementation(libs.landscapist.glide)

  // webrtc
  implementation(libs.webrtc)
  implementation(libs.okhttp.logging)

  // coroutines
  implementation(libs.kotlinx.coroutines.android)

  // logger
  implementation(libs.stream.log)

  // AndServer HTTP server
  implementation("com.yanzhenjie.andserver:api:2.1.9")
  kapt("com.yanzhenjie.andserver:processor:2.1.9")
  // processor 内部使用 javax.activation.MimeTypeParseException，但 POM 未声明该传递依赖，
  // 缺失会导致 ControllerProcessor 无法加载（API 全部 404），必须手动补到 kapt classpath
  kapt("javax.activation:activation:1.1.1")

  // WebSocket server for WebRTC signaling
  implementation("org.java-websocket:Java-WebSocket:1.5.7")
}

// ==================== 前端打包与同步 ====================
// 流程：npm run build 打包 frontend -> 清空 assets/web -> 复制 frontend/dist 到 assets/web。
// 挂在 preBuild 上，assembleDebug / assembleRelease 前自动执行，无需手动同步。

val frontendDir = rootProject.file("frontend")
val webAssetsDir = file("src/main/assets/web")

// Windows 下 npm 实际是 npm.cmd，直接写 "npm" 会找不到可执行文件
val npmCommand =
  if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"

val buildFrontend by tasks.registering(Exec::class) {
  group = "frontend"
  description = "使用 npm run build 打包 frontend 项目"
  workingDir = frontendDir
  commandLine(npmCommand, "run", "build")
  // 前端源码不变时跳过重新打包
  inputs.dir(file("$frontendDir/src"))
  inputs.file(file("$frontendDir/package.json"))
  inputs.file(file("$frontendDir/vite.config.ts"))
  inputs.dir(file("$frontendDir/public"))
  outputs.dir(file("$frontendDir/dist"))
}

val syncWebAssets by tasks.registering(Sync::class) {
  group = "frontend"
  description = "清空 assets/web 并复制 frontend/dist（打进 APK 的静态页面）"
  dependsOn(buildFrontend)
  into(webAssetsDir)
  from(file("$frontendDir/dist"))
}

// 构建前自动同步前端产物，保证 assets/web 始终与 frontend 一致
tasks.named("preBuild") {
  dependsOn(syncWebAssets)
}