# Gson reads model fields and generic type tokens reflectively.
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,AnnotationDefault
-keep class com.carmusic.app.ui.model.** { *; }
-keep class com.carmusic.app.ui.LocalCollection { *; }
-keep,allowobfuscation,allowoptimization class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.reflect.TypeToken { *; }
