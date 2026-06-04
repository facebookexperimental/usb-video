plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.beanotherlab.usbvideounitybridge"
  compileSdk = 34

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
    }
  }

  defaultConfig {
    minSdk = 24

    // Quest only
    ndk {
      abiFilters += "arm64-v8a"
    }

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("consumer-rules.pro")
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  kotlinOptions {
    jvmTarget = "11"
  }
}

dependencies {
  compileOnly(files("libs/classes.jar"))

  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("com.google.android.material:material:1.12.0")

  testImplementation("junit:junit:4.13.2")

  androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
  androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

/*
 * Copy generated AAR directly into Unity
 */
tasks.register<Copy>("installUnityPlugin") {

  from(layout.buildDirectory.dir("outputs/aar")) {
    include("*.aar")
  }

  into("../Camera Pipeline Validation/Assets/Plugins/Android")

  doLast {
    println("=== COPIED AAR TO UNITY ===")
  }
}

/*
 * Run automatically whenever an AAR is produced
 */
tasks.matching { it.name == "bundleDebugAar" }
  .configureEach {
    finalizedBy("installUnityPlugin")
  }

tasks.matching { it.name == "bundleReleaseAar" }
  .configureEach {
    finalizedBy("installUnityPlugin")
  }