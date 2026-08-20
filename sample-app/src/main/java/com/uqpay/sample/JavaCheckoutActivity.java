package com.uqpay.sample;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.uqpay.sdk.UQPay;
import com.uqpay.sdk.error.UQPayError;
import com.uqpay.sdk.error.UQPayErrorCode;
import com.uqpay.sdk.payment.PaymentResult;
import com.uqpay.sdk.payment.PaymentSessionParams;
import com.uqpay.sdk.payment.UQPayPaymentLauncher;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The same checkout as {@link MainActivity}, written in Java.
 *
 * <p>The SDK is Kotlin, and "it is Java-friendly" is a claim that only means something if
 * somebody compiles it. The SDK's own {@code src/test/java} source set catches a lost
 * {@code @JvmStatic} or {@code @JvmOverloads}, but a unit test never launches an Activity,
 * never registers an {@code ActivityResultContract} and never receives a real callback.
 * This screen does all three, from Java, in an app.
 *
 * <p>Every Java-specific claim the integration guide makes is exercised below:
 *
 * <ul>
 *   <li>{@code UQPay.createPaymentLauncher} is static, and {@code PaymentCallback} is a
 *       single-method interface, so a lambda works.
 *   <li>{@code new PaymentSessionParams(intentId)} works without naming a presentation —
 *       {@code @JvmOverloads}, not a hand-written overload.
 *   <li>{@code BillingDetails.Builder} exists because Java has no named arguments.
 *   <li>{@code PaymentStatus} is an enum, so {@code switch} works.
 *   <li>{@code UQPayErrorCode} is deliberately <b>not</b> an enum, so it is compared with
 *       {@code equals} and never switched over.
 * </ul>
 *
 * <p>This is a demo host, not a template for a production checkout — for that, read
 * {@code MainActivity} and the header on {@code DemoMerchantBackend}.
 */
public final class JavaCheckoutActivity extends AppCompatActivity {

    private static final String TAG = "UQPaySampleJava";

    private UQPayPaymentLauncher payments;
    private Button checkoutButton;
    private ProgressBar progress;
    private TextView status;

    /**
     * One background thread for the pretend backend call. A real app uses whatever it
     * already has. What matters is that no network call runs on the main thread.
     */
    private final ExecutorService backgroundWork = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_java_checkout);

        checkoutButton = findViewById(R.id.btn_checkout);
        progress = findViewById(R.id.progress);
        status = findViewById(R.id.txt_status);

        ((TextView) findViewById(R.id.txt_total)).setText(
                getString(R.string.price_amount, Cart.INSTANCE.getTotal().toPlainString()));

        // Static, and PaymentCallback is a single-method interface — so this is a method
        // reference from Java, exactly as it is from Kotlin.
        //
        // Created unconditionally in onCreate, on every Activity creation. That is what
        // lets a result come back after Android kills and recreates the process
        // mid-payment. Registering it on the button tap instead loses results silently.
        payments = UQPay.createPaymentLauncher(this, this::onPaymentResult);

        String setupProblem = DemoMerchantBackend.INSTANCE.setupProblem();
        if (setupProblem != null) {
            checkoutButton.setEnabled(false);
            showStatus(setupProblem);
        } else {
            checkoutButton.setOnClickListener(v -> checkout());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        backgroundWork.shutdown();
    }

    // ---- checkout -------------------------------------------------------------------------

    private void checkout() {
        setWorking(true);
        backgroundWork.execute(() -> {
            String intentId = null;
            String failure = null;
            try {
                intentId = DemoMerchantBackend.INSTANCE.createPaymentIntent(
                        Cart.INSTANCE.getTotal(), Cart.INSTANCE.description());
            } catch (RuntimeException e) {
                // The message, not the exception: a stack trace from a demo backend is not
                // the interesting part, and this string goes on screen.
                failure = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }

            final String createdIntentId = intentId;
            final String createFailure = failure;
            runOnUiThread(() -> {
                setWorking(false);
                if (createdIntentId != null) {
                    startPayment(createdIntentId);
                } else {
                    showStatus(getString(R.string.status_checkout_failed, createFailure));
                }
            });
        });
    }

    private void startPayment(String paymentIntentId) {
        showStatus(getString(R.string.status_starting));

        // Java has no named arguments, and BillingDetails has ten String parameters in a
        // row. Positionally, `new BillingDetails(…, "Klang", "Selangor", …)` with city and
        // state transposed compiles exactly as cleanly as the correct call, and ships wrong
        // AVS data forever. The builder is the whole reason that mistake is not available
        // here — every value is named at the call site.
        PaymentSessionParams.BillingDetails billing =
                new PaymentSessionParams.BillingDetails.Builder()
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

        // `Presentation.MethodList.INSTANCE`, spelled out, because Java has no default
        // arguments: @JvmOverloads generates (intentId), (intentId, presentation) and
        // (intentId, presentation, billingDetails) — but no (intentId, billingDetails), so
        // a Java caller who wants the default sheet *and* a prefill has to name the default.
        // Passing null here would not compile away to the default; `presentation` is
        // non-null in Kotlin, so it would throw at the boundary.
        payments.launch(new PaymentSessionParams(
                paymentIntentId,
                PaymentSessionParams.Presentation.MethodList.INSTANCE,
                billing));
    }

    private void setWorking(boolean working) {
        checkoutButton.setEnabled(!working);
        checkoutButton.setText(working ? R.string.checkout_working : R.string.checkout_button);
        progress.setVisibility(working ? View.VISIBLE : View.GONE);
    }

    // ---- the four outcomes ------------------------------------------------------------------

    /**
     * Delivered exactly once per launch, on the main thread, across rotation and process
     * death. {@code PaymentStatus} is an enum, so an ordinary switch covers it — and the
     * compiler will point at this method the day a fifth status is added.
     */
    private void onPaymentResult(PaymentResult result) {
        switch (result.getStatus()) {
            case SUCCEEDED:
                // Advisory. A real app confirms with its backend, which knows the webhook
                // outcome, before fulfilling anything.
                showStatus(getString(R.string.status_succeeded, result.getPaymentIntentId()));
                break;

            case FAILED:
                showStatus(getString(R.string.status_failed, describe(result.getError())));
                break;

            case CANCELLED:
                showStatus(getString(R.string.status_cancelled));
                break;

            case PENDING:
                // A payment was submitted and its outcome is not known. Do not retry it, do
                // not refund it, do not release the order. The webhook is the answer.
                showStatus(getString(R.string.status_pending));
                break;
        }
    }

    /**
     * Picks the sentence that goes on screen, and logs the one that does not.
     *
     * <p>{@code message} is written for the shopper. {@code developerMessage} is written for
     * you: English-only, unstable, and never fit to show a customer.
     *
     * <p>Note what this does <b>not</b> do: switch over {@code error.getCode()}.
     * {@code UQPayErrorCode} is an open set rather than an enum precisely so that UQPAY can
     * add a code without breaking a merchant's build, which means Java cannot switch on it
     * and must compare with {@code equals}. Unrecognised codes arrive verbatim through
     * {@code UQPayErrorCode.of(raw)} rather than collapsing to {@code UNKNOWN}.
     */
    private String describe(@Nullable UQPayError error) {
        if (error == null) {
            return "";
        }

        if (error.getDeveloperMessage() != null) {
            Log.w(TAG, "payment failed [" + error.getCode() + "]: " + error.getDeveloperMessage());
        }

        if (UQPayErrorCode.NETWORK_ERROR.equals(error.getCode())) {
            // A code worth branching on: the customer can act on it, and the SDK reached
            // nobody, so nothing was charged.
            return error.getMessage() + "\n\nCheck the connection and try again.";
        }
        return error.getMessage();
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }
}
