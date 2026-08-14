package com.uqpay.sdk

/** Target UQPAY environment. Keys are environment-specific and not interchangeable. */
public enum class Environment {
    /** Test environment — no real money moves. */
    SANDBOX,

    /** Live environment — real payments. */
    PRODUCTION,
}
