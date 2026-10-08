# SPDX-License-Identifier: AGPL-3.0-or-later
# linphone-sdk ships its own consumer rules (proguard.txt in the AAR). kotlinx.serialization: keep the generated
# serializers of our API models.
-keepclassmembers @kotlinx.serialization.Serializable class nl.fullstackstudio.fsvoip.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.journeyapps.barcodescanner.** { *; }
