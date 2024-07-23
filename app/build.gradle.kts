/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.meta.usbvideo"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.meta.usbvideo"
    minSdk = 30
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    externalNativeBuild {
      cmake {
        cppFlags += "-stdlib=libc++ -std=c++20 -fexperimental-library -fvisibility=hidden"
      }
    }
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
  kotlinOptions {
    jvmTarget = "1.8"
  }
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
  buildFeatures {
    viewBinding = true
  }
  ndkVersion = "26.3.11579264"
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.activity:activity:1.9.0")
  implementation("androidx.activity:activity-ktx:1.9.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.3")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
  implementation("androidx.startup:startup-runtime:1.1.1")
  testImplementation("junit:junit:4.13.2")
  testImplementation(kotlin("test"))
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
  testImplementation("io.mockk:mockk-android:1.13.12")
  testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
  androidTestImplementation("androidx.test.ext:junit:1.2.1")
  androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
  androidTestImplementation("io.mockk:mockk-android:1.13.12")
}
