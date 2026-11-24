# SnakeYAML references java.beans on the desktop JDK, but Android does not ship it.
# The library already falls back when those types are unavailable.
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

# SnakeYAML creates loggers with Logger.getLogger(X.class.getPackage().getName())
# (TypeDescription, among others). Moved into the unnamed package by R8, its
# classes have no Package, the static initializer throws and every YAML
# (Clash) import fails in release builds. Keep the package names.
-keeppackagenames org.yaml.snakeyaml.**

# Readable stack traces in release crash logs (decode with the R8 mapping file).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNI entry points of the Tailscale bridge (libtailscalebridgejni.so binds them by name).
-keep class com.mallocgfw.app.tailscale.TailscaleNative { native <methods>; }

# ML Kit finds its component registrars by class name (manifest metadata) and
# creates them through the no-argument constructor.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
