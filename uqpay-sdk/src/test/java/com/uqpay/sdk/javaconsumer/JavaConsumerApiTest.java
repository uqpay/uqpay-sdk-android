package com.uqpay.sdk.javaconsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.os.Bundle;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultCaller;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import com.uqpay.sdk.appearance.UQPayAppearance;
import androidx.test.core.app.ApplicationProvider;

import com.uqpay.sdk.Environment;
import com.uqpay.sdk.UQPay;
import com.uqpay.sdk.UQPayConfiguration;
import com.uqpay.sdk.auth.UQPayAuthToken;
import com.uqpay.sdk.auth.UQPayTokenProvider;
import com.uqpay.sdk.error.UQPayError;
import com.uqpay.sdk.error.UQPayErrorCode;
import com.uqpay.sdk.payment.PaymentCallback;
import com.uqpay.sdk.payment.PaymentMethodType;
import com.uqpay.sdk.payment.PaymentResult;
import com.uqpay.sdk.payment.PaymentSessionParams;
import com.uqpay.sdk.payment.PaymentStatus;
import com.uqpay.sdk.payment.UQPayPaymentLauncher;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AC §5.3 — <b>a Java host app can consume the public API without friction.</b>
 *
 * <p>This file is deliberately plain Java 17, in its own source set, written the way a
 * merchant's {@code CheckoutActivity} would be. It is the only thing that proves the
 * {@code @JvmStatic} / {@code @JvmOverloads} / SAM-interface decisions on the public surface
 * actually work: a Kotlin test cannot fail when {@code @JvmStatic} is dropped from
 * {@code UQPay.initialize}, because Kotlin calls the object instance either way. This one
 * stops compiling.
 *
 * <p>Every call below is a compile-time assertion first and a runtime assertion second. If
 * the public API ever regresses into something Java cannot reach — a Kotlin default argument
 * with no overload, a member moved off the companion, a functional interface that stops being
 * a SAM — this file fails to build, which is the point.
 *
 * <p>No real card number, key or API secret appears in this file.
 */
@RunWith(RobolectricTestRunner.class)
public class JavaConsumerApiTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    @After
    public void tearDown() {
        // `UQPay.resetForTest()` is internal, so its JVM name carries a module suffix Java
        // cannot spell. Found by prefix rather than hard-coded, so a module rename does not
        // silently stop resetting and leak an initialised SDK into the next test class.
        for (Method method : UQPay.class.getDeclaredMethods()) {
            if (method.getName().startsWith("resetForTest")) {
                method.setAccessible(true);
                try {
                    method.invoke(UQPay.INSTANCE);
                } catch (ReflectiveOperationException e) {
                    fail("could not reset the SDK: " + e);
                }
                return;
            }
        }
        fail("UQPay.resetForTest was not found; this test would leak state into the next class");
    }

    /** {@code UQPay.initialize} is reachable statically, and the token provider is a SAM. */
    @Test
    public void initializeIsStaticAndTheTokenProviderIsALambda() {
        assertFalse(UQPay.isInitialized());

        UQPayTokenProvider provider = () -> new UQPayAuthToken("tok-fixture", System.currentTimeMillis() + 1800_000L);
        UQPayConfiguration configuration = new UQPayConfiguration("client-test", Environment.SANDBOX, provider);

        UQPay.initialize(context, configuration);

        assertTrue(UQPay.isInitialized());
        assertNotNull(UQPay.getVersion());
        assertEquals("client-test", configuration.getClientId());
        assertSame(Environment.SANDBOX, configuration.getEnvironment());
        // A merchant's crash reporter will stringify this. It must never carry the provider.
        assertFalse(configuration.toString().contains("tok-fixture"));
        assertEquals("tok-fixture", configuration.getTokenProvider().fetchToken().getValue());
    }

    /**
     * The launcher, created the way the integration guide tells a Java merchant to create it:
     * unconditionally in {@code onCreate}, before the Activity is STARTED, with a lambda
     * callback.
     */
    @Test
    public void createPaymentLauncherAcceptsALambdaCallback() {
        UQPay.initialize(context, configuration());

        AtomicReference<PaymentResult> delivered = new AtomicReference<>();
        PaymentCallback callback = delivered::set;

        ComponentActivity activity = Robolectric.buildActivity(ComponentActivity.class).create().get();
        UQPayPaymentLauncher launcher = UQPay.createPaymentLauncher(activity, callback);

        assertNotNull(launcher);
        assertNull(delivered.get());
    }

    /** Launching before {@code initialize} is a programmer error, and says so. */
    @Test
    public void createPaymentLauncherBeforeInitializeThrowsIllegalState() {
        ComponentActivity activity = Robolectric.buildActivity(ComponentActivity.class).create().get();
        try {
            UQPay.createPaymentLauncher(activity, result -> { });
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("UQPay.initialize"));
        }
    }

    /**
     * {@code PaymentSessionParams} must be constructible from Java with the intent id alone —
     * that is what {@code @JvmOverloads} on its constructor buys, and without it a Java
     * merchant would have to name a presentation on every launch.
     */
    @Test
    public void paymentSessionParamsHasAJavaFriendlyOverload() {
        PaymentSessionParams simple = new PaymentSessionParams("PI_java_consumer");
        assertEquals("PI_java_consumer", simple.getPaymentIntentId());
        assertSame(PaymentSessionParams.Presentation.MethodList.INSTANCE, simple.getPresentation());

        PaymentSessionParams cardOnly =
                new PaymentSessionParams("PI_java_consumer", PaymentSessionParams.Presentation.CardOnly.INSTANCE);
        assertSame(PaymentSessionParams.Presentation.CardOnly.INSTANCE, cardOnly.getPresentation());

        PaymentSessionParams wallet = new PaymentSessionParams(
                "PI_java_consumer",
                new PaymentSessionParams.Presentation.SingleWallet(PaymentMethodType.GRABPAY));
        PaymentSessionParams.Presentation presentation = wallet.getPresentation();
        assertTrue(presentation instanceof PaymentSessionParams.Presentation.SingleWallet);
        assertEquals(
                PaymentMethodType.GRABPAY,
                ((PaymentSessionParams.Presentation.SingleWallet) presentation).getMethod());
    }

    /**
     * The billing prefill, from Java. Two things are asserted at once, and the first is a
     * compile-time assertion: adding {@code billingDetails} to
     * {@code PaymentSessionParams} must NOT have disturbed the one- and two-argument
     * constructions above — this file would stop compiling if it had.
     *
     * <p>{@code BillingDetails} carries {@code @JvmOverloads} too, so a Java merchant who
     * knows only a name and a country does not have to spell eight nulls.
     *
     * <p>No real person's details appear here.
     */
    @Test
    public void billingDetailsIsConstructibleAndReadableFromJava() {
        PaymentSessionParams.BillingDetails billing = new PaymentSessionParams.BillingDetails(
                "John",
                "Tan",
                "john.tan@example.com",
                "+6591234567",
                "123 Orchard Road",
                "#12-01",
                "Singapore",
                "Singapore",
                "238888",
                "SG");

        PaymentSessionParams params = new PaymentSessionParams(
                "PI_java_consumer",
                PaymentSessionParams.Presentation.MethodList.INSTANCE,
                billing);

        assertEquals("PI_java_consumer", params.getPaymentIntentId());
        assertNotNull(params.getBillingDetails());
        assertEquals("John", params.getBillingDetails().getFirstName());
        assertEquals("Tan", params.getBillingDetails().getLastName());
        assertEquals("john.tan@example.com", params.getBillingDetails().getEmail());
        assertEquals("+6591234567", params.getBillingDetails().getPhone());
        assertEquals("123 Orchard Road", params.getBillingDetails().getAddressLine1());
        assertEquals("#12-01", params.getBillingDetails().getAddressLine2());
        assertEquals("Singapore", params.getBillingDetails().getCity());
        assertEquals("Singapore", params.getBillingDetails().getState());
        assertEquals("238888", params.getBillingDetails().getPostalCode());
        assertEquals("SG", params.getBillingDetails().getCountryCode());

        // A crash reporter stringifying the launch params must not capture the customer.
        assertFalse(params.toString().contains("john.tan@example.com"));
        assertFalse(params.toString().contains("+6591234567"));
        assertFalse(billing.toString().contains("john.tan@example.com"));

        // @JvmOverloads: the trailing optionals can be omitted from Java.
        PaymentSessionParams.BillingDetails nameOnly = new PaymentSessionParams.BillingDetails("John");
        assertEquals("John", nameOnly.getFirstName());
        assertNull(nameOnly.getLastName());
        assertNull(nameOnly.getCountryCode());
        assertNotNull(new PaymentSessionParams.BillingDetails());

        // And the launch a merchant with no prefill writes is unchanged.
        assertNull(new PaymentSessionParams("PI_java_consumer").getBillingDetails());
        assertNull(new PaymentSessionParams(
                "PI_java_consumer",
                PaymentSessionParams.Presentation.CardOnly.INSTANCE).getBillingDetails());
    }

    /**
     * The launcher from a <b>Fragment</b>, which is the host half of merchant apps that this
     * SDK could not serve at all until {@code createPaymentLauncher} took an
     * {@link androidx.activity.result.ActivityResultCaller}. Both {@code ComponentActivity}
     * and {@code Fragment} implement it, so one overload covers both with no new dependency.
     *
     * <p>Compile-time assertion first: if the overload is ever narrowed back to
     * {@code ComponentActivity}, this stops building.
     */
    @Test
    public void createPaymentLauncherAcceptsAnyActivityResultCaller() {
        UQPay.initialize(context, configuration());

        FragmentActivity host = Robolectric.buildActivity(FragmentActivity.class).setup().get();
        CheckoutFragment fragment = new CheckoutFragment();
        host.getSupportFragmentManager().beginTransaction().add(fragment, "checkout").commitNow();

        assertNotNull(fragment.payments);
    }

    /**
     * A Fragment host written the way the integration guide tells a merchant to write one:
     * the launcher is created in {@code onCreate}, unconditionally, every time. The framework
     * enforces this — registering any later throws — which is the same rule the Activity path
     * has, stated in the Fragment's own vocabulary.
     */
    public static class CheckoutFragment extends Fragment {
        UQPayPaymentLauncher payments;

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // `this` is an ActivityResultCaller, not a ComponentActivity. If the overload is
            // ever narrowed back, this line stops compiling.
            ActivityResultCaller caller = this;
            payments = UQPay.createPaymentLauncher(caller, result -> { });
        }
    }

    /**
     * {@code cancel()} is on the interface a merchant holds, and is a documented no-op before
     * any launch — a merchant's "the order was cancelled from the back office" handler must
     * be safe to run whether or not a sheet is on screen.
     */
    @Test
    public void theLauncherCanBeCancelledFromJava() {
        UQPay.initialize(context, configuration());

        ComponentActivity activity = Robolectric.buildActivity(ComponentActivity.class).create().get();
        UQPayPaymentLauncher launcher = UQPay.createPaymentLauncher(activity, result -> { });

        launcher.cancel();
    }

    /**
     * The billing prefill through its builder, which is the shape a Java merchant should use.
     * Ten {@code String} parameters in a row is where {@code city} and {@code state} get
     * transposed and wrong AVS data ships forever; the builder puts the field name beside the
     * value, and the compiler will not let a name be wrong.
     */
    @Test
    public void billingDetailsHasAJavaBuilder() {
        PaymentSessionParams.BillingDetails built = new PaymentSessionParams.BillingDetails.Builder()
                .firstName("John")
                .lastName("Tan")
                .email("john.tan@example.com")
                .phone("+6591234567")
                .addressLine1("123 Orchard Road")
                .addressLine2("#12-01")
                .city("Singapore")
                .state("Singapore")
                .postalCode("238888")
                .countryCode("SG")
                .build();

        assertEquals("Singapore", built.getCity());
        assertEquals("Singapore", built.getState());
        assertEquals("SG", built.getCountryCode());
        assertNotNull(new PaymentSessionParams.BillingDetails.Builder().build());
    }

    /**
     * The payment-method allow-list, from Java: a {@code Set} on the launch params, and a
     * presentation that contradicts it is refused at launch rather than half-honoured.
     */
    @Test
    public void theAllowListIsSettableFromJava() {
        Set<PaymentMethodType> allowed = new LinkedHashSet<>();
        allowed.add(PaymentMethodType.CARD);
        allowed.add(PaymentMethodType.PAYNOW);

        PaymentSessionParams params = new PaymentSessionParams(
                "PI_java_consumer",
                PaymentSessionParams.Presentation.MethodList.INSTANCE,
                null,
                allowed);

        assertEquals(allowed, params.getAllowedPaymentMethods());
        assertNull(new PaymentSessionParams("PI_java_consumer").getAllowedPaymentMethods());
    }

    /**
     * The appearance API, from Java. Colours are plain ARGB ints so no Compose type appears
     * in the public surface, and both builders exist because ten adjacent ints and two
     * adjacent palettes are exactly what a positional constructor gets wrong.
     */
    @Test
    public void theAppearanceIsConfigurableFromJava() {
        UQPayAppearance.Colors brand = new UQPayAppearance.Colors.Builder(UQPayAppearance.Colors.MATERIAL_LIGHT)
                .primary(0xFF0B5FFF)
                .onPrimary(0xFFFFFFFF)
                .build();

        UQPayAppearance appearance = new UQPayAppearance.Builder()
                .colorMode(UQPayAppearance.ColorMode.LIGHT)
                .lightColors(brand)
                .cornerRadiusDp(4f)
                .build();

        UQPayConfiguration configured = new UQPayConfiguration(
                "client-java-consumer",
                Environment.SANDBOX,
                () -> new UQPayAuthToken("tok", System.currentTimeMillis() + 60_000L),
                false,
                appearance);

        assertEquals(0xFF0B5FFF, configured.getAppearance().getLightColors().getPrimary());
        assertEquals(UQPayAppearance.ColorMode.LIGHT, configured.getAppearance().getColorMode());
        assertEquals(4f, configured.getAppearance().getCornerRadiusDp(), 0f);

        // Omitted entirely, the default is stock Material 3 following the device.
        assertEquals(
                UQPayAppearance.ColorMode.SYSTEM,
                configuration().getAppearance().getColorMode());
    }

    /** Reading a result: the switch a merchant writes in their callback. */
    @Test
    public void aResultIsReadableAndPaymentStatusSwitches() {
        PaymentResult result = succeeded();

        String branch;
        switch (result.getStatus()) {
            case SUCCEEDED:
                branch = "capture:" + result.getPaymentIntentId();
                break;
            case FAILED:
                branch = "error";
                break;
            case CANCELLED:
                branch = "cancelled";
                break;
            case PENDING:
                branch = "await-webhook";
                break;
            default:
                throw new AssertionError("PaymentStatus gained a member; every merchant switch is now incomplete");
        }

        assertEquals("capture:PI_java_consumer", branch);
        assertEquals(new BigDecimal("8.98"), result.getAmount());
        assertEquals("SGD", result.getCurrency());
        assertEquals(PaymentMethodType.CARD, result.getPaymentMethodType());
        assertEquals("order-1", result.getMerchantOrderId());
        assertEquals(Long.valueOf(1_755_500_000_000L), result.getCompletedAtEpochMillis());
        assertNull(result.getError());
    }

    /**
     * Error codes are compared, never switched — the type is deliberately not an enum so an
     * unrecognised gateway code can be carried rather than dropped. Java sees the constants
     * as static fields and {@code of} as a static method.
     */
    @Test
    public void errorCodesCompareAndCarryUnknownValues() {
        UQPayError error = new UQPayError(
                UQPayErrorCode.CARD_DECLINED,
                "Your card was declined.",
                "insufficient_funds",
                null,
                "UQPAY answered HTTP 402 with code=card_declined.");

        assertEquals(UQPayErrorCode.CARD_DECLINED, error.getCode());
        assertFalse(UQPayErrorCode.NETWORK_ERROR.equals(error.getCode()));
        assertEquals("insufficient_funds", error.getDeclineCode());
        assertNull(error.getTraceId());

        // The two messages are separate getters, and Java sees both. `getMessage` is the
        // shopper's; `getDeveloperMessage` is the log line's and must never reach a screen.
        assertEquals("Your card was declined.", error.getMessage());
        assertEquals("UQPAY answered HTTP 402 with code=card_declined.", error.getDeveloperMessage());

        assertEquals(UQPayErrorCode.CARD_DECLINED, UQPayErrorCode.of("card_declined"));
        assertEquals("a code this SDK version predates is carried, not dropped",
                "some_future_code", UQPayErrorCode.of("some_future_code").getRaw());

        assertEquals(PaymentMethodType.GRABPAY, PaymentMethodType.of("grabpay"));
        assertEquals("futurepay", PaymentMethodType.of("futurepay").getRaw());
    }

    // ---- fixtures ---------------------------------------------------------------------

    private static UQPayConfiguration configuration() {
        return new UQPayConfiguration(
                "client-test",
                Environment.SANDBOX,
                () -> new UQPayAuthToken("tok-fixture", System.currentTimeMillis() + 1800_000L));
    }

    private static PaymentResult succeeded() {
        return new PaymentResult(
                PaymentStatus.SUCCEEDED,
                "PI_java_consumer",
                PaymentMethodType.CARD,
                new BigDecimal("8.98"),
                "SGD",
                "order-1",
                "PA_1",
                1_755_500_000_000L,
                null);
    }
}
