# BruDre Streaming — regras do R8 (release)

# libtorrent4j: classes Java chamadas pelo código nativo via JNI (SWIG).
-keep class org.libtorrent4j.** { *; }
-keepclassmembers class org.libtorrent4j.swig.** { *; }
-dontwarn org.libtorrent4j.**

# Classes usadas por reflexão/serviços do Android já são mantidas pelas regras das bibliotecas
# (Room, Media3, OkHttp, Coil, Compose). Mensagens de log com nomes legíveis:
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp / Conscrypt opcionais
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
