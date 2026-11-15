# No app-specific rules needed: MapLibre ships its own, nothing else uses reflection.

# JNI: llama_jni.cpp looks these up by name.
-keep class com.iameberhard.icharge.ai.LlamaNative { *; }
-keep class com.iameberhard.icharge.ai.LlamaNative$TokenListener { *; }
-keepclassmembers class * implements com.iameberhard.icharge.ai.LlamaNative$TokenListener { public boolean onToken(byte[]); }
