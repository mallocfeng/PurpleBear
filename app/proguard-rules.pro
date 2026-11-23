# SnakeYAML references java.beans on the desktop JDK, but Android does not ship it.
# The library already falls back when those types are unavailable.
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

# Readable stack traces in release crash logs (decode with the R8 mapping file).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# JNI entry points of the Tailscale bridge (libtailscalebridgejni.so binds them by name).
-keep class com.mallocgfw.app.tailscale.TailscaleNative { native <methods>; }

# ML Kit finds its component registrars by class name (manifest metadata) and
# creates them through the no-argument constructor.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
