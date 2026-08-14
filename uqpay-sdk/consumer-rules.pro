# UQPAY SDK consumer rules — applied automatically to apps that depend on this AAR.
# Keep the public API surface intact under R8/ProGuard.
-keep public class com.uqpay.sdk.UQPay { public *; }
-keep public class com.uqpay.sdk.UQPayConfiguration { public *; }
-keep public class com.uqpay.sdk.Environment { *; }
-keep public class com.uqpay.sdk.payment.** { public *; }
-keep public class com.uqpay.sdk.error.** { public *; }
