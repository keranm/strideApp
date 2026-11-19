plugins {
    id("com.android.application") version "8.7.3" apply false
    // 2.2.10 to match the kotlin-stdlib that GeckoView's dependencies pin with a
    // `strictly` constraint (the gecko flavor). Source-compatible for this code.
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}
