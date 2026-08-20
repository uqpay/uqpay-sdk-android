# UQPAY SDK consumer rules — applied automatically to apps that depend on this AAR.
#
# Deliberately surgical. A blanket `-keep class com.uqpay.sdk.** { *; }` would disable
# shrinking and obfuscation of the whole SDK inside every merchant app; R8 already keeps
# the public API that a merchant actually references.
#
# The same surgical rule applies to what we keep *outside* our own package. A consumer rule
# is a rule the merchant did not write and cannot see without unzipping our AAR, so every
# line here has to justify the shrinking it takes away from their build.

# ActivityResultRegistry matches contracts across process death. If R8 strips or renames
# this one, the payment result is silently lost — and only in release builds.
#
# Named exactly, not matched by supertype. This used to read
# `-keep class * extends androidx.activity.result.contract.ActivityResultContract`, which
# keeps *every* contract in the merchant's app: androidx's own StartActivityForResult,
# RequestPermission, PickVisualMedia, and every contract the merchant or any other library
# ever wrote. None of those are ours to protect, and a payment SDK quietly disabling
# optimisation across an unrelated part of someone else's app is not a defensible default.
-keep class com.uqpay.sdk.launcher.UQPayPaymentContract { *; }

# kotlinx.serialization resolves enum constants from wire strings by name, so they look
# unused to R8. Plain @Serializable classes need no rule (their serializers are generated
# at compile time).
-keep @kotlinx.serialization.Serializable enum com.uqpay.sdk.** { *; }
