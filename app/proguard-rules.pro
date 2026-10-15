# R8/ProGuard rules

-dontwarn io.github.libxposed.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep class com.hazbu.xcam.** { *; }

-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }
-keep interface io.github.libxposed.service.** { *; }

-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

-keepattributes *Annotation*,Signature,EnclosingMethod,InnerClasses,SourceFile,LineNumberTable

-dontoptimize
