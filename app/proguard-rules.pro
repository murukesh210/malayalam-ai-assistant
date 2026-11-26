-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @com.google.ai.edge.litertlm.Tool <methods>;
}
