plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace="dev.personal.photostv"; compileSdkVersion("android-37.0")
 defaultConfig { applicationId="dev.personal.photostv"; minSdk=26; targetSdk=36; versionCode=20; versionName="0.3.0"; testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner" }
 flavorDimensions += "device"
 productFlavors { create("phone") { dimension="device"; applicationIdSuffix=".phone" }; create("tv") { dimension="device"; applicationIdSuffix=".tv" } }
 buildFeatures { compose=true; buildConfig=true }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget="17" }
}
dependencies {
 implementation("androidx.activity:activity-compose:1.13.0")
 implementation(platform("androidx.compose:compose-bom:2026.09.00"))
 implementation("androidx.compose.material3:material3")
 implementation("androidx.tv:tv-material:1.1.0")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
 implementation("androidx.media3:media3-exoplayer:1.11.1")
 implementation("androidx.media3:media3-ui:1.11.1")
 implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
 implementation("com.google.android.gms:play-services-auth:22.0.0")
 implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
 "phoneImplementation"("androidx.camera:camera-camera2:1.6.2")
 "phoneImplementation"("androidx.camera:camera-lifecycle:1.6.2")
 "phoneImplementation"("androidx.camera:camera-view:1.6.2")
 "phoneImplementation"("com.google.mlkit:barcode-scanning:17.3.0")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
 implementation("org.nanohttpd:nanohttpd:2.3.1")
 implementation("com.google.zxing:core:3.5.3")
 implementation("io.coil-kt:coil-compose:2.7.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20250517")
 androidTestImplementation("androidx.test.ext:junit:1.3.0")
 androidTestImplementation("androidx.test:runner:1.7.0")
}

// Keep network/buffer regression failures diagnosable in CI, including coroutine stacks.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
 testLogging.exceptionFormat=org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}
