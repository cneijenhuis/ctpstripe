package com.commercetools.pspadapter.stripe;

import com.commercetools.pspadapter.stripe.executors.IdempotentChargeExecutor;
import com.commercetools.pspadapter.stripe.executors.IdempotentCustomerExecutor;
import com.commercetools.pspadapter.stripe.util.PaymentHelperMethods;
import com.commercetools.pspadapter.stripe.util.PaymentPair;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.payments.Payment;
import io.sphere.sdk.payments.messages.PaymentCreatedMessage;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class PaymentCreationListener extends PaymentHelperMethods {
    final private SphereClient client;

    public PaymentCreationListener(SphereClient client) {
        super(new TypeKeyToId(client));
        this.client = client;
    }

    public void paymentCreated(PaymentCreatedMessage msg) throws Exception {
        final Payment payment = msg.getResource().getObj();
        final boolean chargeRequested = true; // TODO
        if (chargeRequested) {
            getOrCreateStripeCustomerId(payment)
            .thenCompose(pair -> getOrCreateCharge(pair))
            .get()
            .getRight()
            .map(chargeId -> {
                System.out.println("Charge: " + chargeId);
                return chargeId;
            });

        }
        else if (payment.getCustomer() != null) {
            getOrCreateStripeCustomerId(payment)
            .get()
            .getRight()
            .map(customerId -> {
                System.out.println("Customer: " + customerId);
                return customerId;
            });
        }
    }

    private CompletableFuture<PaymentPair<Optional<String>>> getOrCreateCharge(PaymentPair<Optional<String>> pair) {
        return pair.getRight()
            .map(stripeCustomerId ->
                    new IdempotentChargeExecutor(pair.getLeft(), stripeCustomerId, client).executionResult()
            )
            .orElse(CompletableFuture.completedFuture(pair)) ;
    }

    private CompletableFuture<PaymentPair<Optional<String>>> getOrCreateStripeCustomerId(Payment payment) {
        return new IdempotentCustomerExecutor(payment, client).executionResult();
    }
}
