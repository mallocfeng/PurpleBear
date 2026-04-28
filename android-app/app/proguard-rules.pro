# SnakeYAML references java.beans on the desktop JDK, but Android does not ship it.
# The library already falls back when those types are unavailable.
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
