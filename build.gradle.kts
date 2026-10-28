// Top-level build file.
// NOTE: there is deliberately NO `org.jetbrains.kotlin.android` plugin here.
// Android Gradle Plugin 8.0+ has built-in Kotlin support, so applying the
// kotlin-android plugin would fail with:
//   "Cannot add extension with name 'kotlin', as there is an extension already registered"

plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "1.9.20" apply false
}
