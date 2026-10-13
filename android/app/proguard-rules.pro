# Tor usa JNI: sus clases y métodos nativos no se pueden renombrar.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-dontwarn javax.annotation.**
