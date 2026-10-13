buildscript {
    dependencies {
        // Versión de Kotlin usada por el soporte integrado de AGP 9 (GeckoView requiere Kotlin 2.4)
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}
