# UQPAY SDK consumer rules — applied automatically to apps that depend on this AAR.
#
# Deliberately surgical. A blanket `-keep class com.uqpay.sdk.** { *; }` would disable
# shrinking and obfuscation of the whole SDK inside every merchant app; R8 already keeps
# the public API that a merchant actually references.

# ActivityResultRegistry matches contracts across process death. If R8 strips or renames
# this one, the payment result is silently lost — and only in release builds.
-keep class * extends androidx.activity.result.contract.ActivityResultContract

# kotlinx.serialization resolves enum constants from wire strings by name, so they look
# unused to R8. Plain @Serializable classes need no rule (their serializers are generated
# at compile time).
-keep @kotlinx.serialization.Serializable enum com.uqpay.sdk.** { *; }
