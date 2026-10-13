# Tor usa JNI: sus clases y métodos nativos no se pueden renombrar.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
-dontwarn javax.annotation.**
# WebAuthn de GeckoView usa Google Play Services, que se excluyen a propósito.
-dontwarn com.google.android.gms.**
# GeckoView lee la configuración (proxy de Tor, preferencias) con SnakeYAML, que usa
# reflexión y Class.getPackage(): si R8 recoloca sus clases, el motor no arranca.
-keep class org.yaml.snakeyaml.** { *; }
-keep class org.mozilla.gecko.util.DebugConfig { *; }
-keep class org.mozilla.gecko.util.DebugConfig$** { *; }
-dontwarn java.beans.**
