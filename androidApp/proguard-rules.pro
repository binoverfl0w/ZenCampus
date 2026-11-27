# Serialization uses generated serializers; no reflective model lookup is used.
-keepattributes Signature,InnerClasses,EnclosingMethod
# Crash reports should name real classes and methods; the source is public, so obfuscation hides nothing worth hiding.
-dontobfuscate
# Line numbers too, so a crash report points at the line; the source is public anyway.
-keepattributes SourceFile,LineNumberTable
