# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep AppAuth classes
-keep class net.openid.appauth.** { *; }

# Keep Google API client classes
-keep class com.google.api.** { *; }
-keep class com.google.apis.** { *; }

# Keep JavaMail classes
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
