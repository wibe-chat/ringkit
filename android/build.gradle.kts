group = "com.wibechat.ringkit"
version = "1.0-SNAPSHOT"

buildscript {
    val kotlinVersion = "2.3.20"
    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.0.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.library")
}

android {
    namespace = "com.wibechat.ringkit"

    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
        }
    }

    defaultConfig {
        minSdk = 26
        // §13: propagate the plugin's R8 keep rules to every consuming app's
        // release build (androidx.work Room DB + workers are reflectively built).
        consumerProguardFiles("consumer-rules.pro")
    }

    testOptions {
        // Robolectric suites (generated from the normative §5.1/§7.5 tables) run on JUnit 4.
        unitTests {
            isIncludeAndroidResources = true
            all {
                // The API compatibility matrix loads multiple Robolectric framework jars.
                it.maxHeapSize = "2g"
                it.outputs.upToDateWhen { false }

                it.testLogging {
                    events("passed", "skipped", "failed", "standardError")
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // PLAN §13 native-dep allowlist. NOT 1.19.x: androidx.core 1.19.0 requires
    // compileSdk 37 + AGP 9.1 (verified empirically 2026-07-05) — our toolchain
    // pins compileSdk 36 / AGP 9.0.1 (Flutter 3.44 template). CallStyle compat
    // has been in core since 1.5; the 1.19 promoted-notification APIs are the
    // Phase-4 Android 16 polish. Revisit with the toolchain bump.
    implementation("androidx.core:core-ktx:1.17.0")
    // D7 / §8.7: the flag-gated telecom/ module. Ships UNCONDITIONALLY (small androidx
    // artifact) — telecomIntegration/useTelecom gates all behavior, not the artifact.
    // 1.0.1 = latest STABLE (re-verified Google Maven 2026-07-07: the 1.1.0 line is
    // alpha-only — 1.1.0-alpha06 — ineligible under the stable-only toolchain rule).
    // NOTE: its AAR contributes MANAGE_OWN_CALLS (normal, auto-granted) to every
    // consumer's merged manifest even when telecom is OFF — documented in INTEGRATION.
    implementation("androidx.core:core-telecom:1.0.1")
    // §10.2 durable killed-state delivery host on API 26–33 (expedited
    // OneTimeWorkRequest, survives process death + reboot) + §11.5 uploader.
    // adapter/ + telemetry/ ONLY — core/ stays zero-WorkManager (PLAN §13.1).
    // 2.10.5: latest mature 2.10 line, compileSdk-36-safe (2.11.x deferred with
    // the androidx.core 1.19.0 toolchain bump).
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    // Mode 2 native FCM fast path (§7.3, D6): the additive c2dm RingKitMessagingReceiver
    // references RemoteMessage only. compileOnly ⇒ NOT shipped — the Mode-2 app's own
    // firebase_messaging supplies the runtime classes; non-Firebase consumers stay
    // Firebase-free (guarded by -dontwarn in consumer-rules.pro + the no-Firebase CI
    // job, §14.2). 25.1.0 = firebase-bom 33.16.0-aligned (PLAN §13.1).
    compileOnly("com.google.firebase:firebase-messaging:25.1.0")

    testImplementation("junit:junit:4.13.2")
    // On the test classpath so the Robolectric module can load RingKitMessagingReceiver
    // (which references RemoteMessage); compileOnly deps are absent from tests. The
    // predicate + manifest tests never construct RemoteMessage (Firebase-free assertions).
    testImplementation("com.google.firebase:firebase-messaging:25.1.0")
    testImplementation("androidx.work:work-testing:2.10.5") // §10.2 host-selection branch tests
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    // Real org.json for code that runs OUTSIDE the Robolectric sandbox (the
    // parameterized suites' @Parameters loaders); android.jar stubs throw there.
    testImplementation("org.json:json:20250517")
}
