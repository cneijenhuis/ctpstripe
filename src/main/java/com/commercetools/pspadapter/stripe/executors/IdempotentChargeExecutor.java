package com.commercetools.pspadapter.stripe.executors;

import com.commercetools.pspadapter.stripe.TypeKeyToId;
import com.commercetools.pspadapter.stripe.util.PaymentPair;
import com.commercetools.pspadapter.stripe.util.StripeExecution;
import com.commercetools.pspadapter.stripe.util.StripeRequest;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.commands.UpdateAction;
import io.sphere.sdk.payments.Payment;
import io.sphere.sdk.payments.TransactionBuilder;
import io.sphere.sdk.payments.TransactionType;
import io.sphere.sdk.payments.commands.PaymentUpdateCommand;
import io.sphere.sdk.payments.commands.updateactions.AddInterfaceInteraction;
import io.sphere.sdk.payments.commands.updateactions.AddTransaction;
import io.sphere.sdk.payments.commands.updateactions.SetAmountPaid;
import io.sphere.sdk.utils.MoneyImpl;
import org.javamoney.moneta.function.MonetaryUtil;

import javax.money.MonetaryAmount;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class IdempotentChargeExecutor extends PaymentHelperMethods implements IdempotentStripeRequestExecutor<Optional<String>>{
    final private Payment payment;
    final private SphereClient client;
    final private String stripeCustomerId;

    public IdempotentChargeExecutor(Payment payment, String stripeCustomerId, SphereClient client) {
        super(new TypeKeyToId(client));
        this.payment = payment;
        this.client = client;
        this.stripeCustomerId = stripeCustomerId;
    }

    @Override
    public Optional<PaymentPair<Optional<String>>> previousExecution() {
        // Idempotency check: Has the charge already been charged?
        final Optional<PaymentPair<Optional<String>>> successfulExecution =
            getLastInteractionOfType(payment, "STRIPE_CHARGED")
                .map(interaction ->
                    new PaymentPair<Optional<String>>(payment, Optional.of(interaction.getFieldAsString("chargeId")))
                );
        if (successfulExecution.isPresent()) return successfulExecution;
        // Idempotency check: Has the charge failed permanently?
        else return
            getLastInteractionOfType(payment, "STRIPE_CHARGE_CREATE_REQUEST")
                // There was a previous request, search for exception with same idempotencyKey.
                .flatMap(interaction -> getLastException(payment, interaction)
                    .map(exception ->
                        new PaymentPair<Optional<String>>(payment, Optional.<String>empty())
                    )
                );
    }

    @Override
    public Optional<CompletableFuture<PaymentPair<Optional<String>>>> retryPreviousExecution() {
        // Idempotency check: Did we already try to charge?
        return getLastInteractionOfType(payment, "STRIPE_CHARGE_CREATE_REQUEST")
            .map(interaction ->
                // Retry the same request
                createChargeFromRequest(CompletableFuture.completedFuture(
                    new PaymentPair(payment, StripeRequest.of(interaction))
                ))
            );
    }

    @Override
    public CompletableFuture<PaymentPair<Optional<String>>> newExecution() {
        // Create the request
        final Map<String, Object> chargeParams = createChargeParams(payment.getAmountPlanned(), true);
        final StripeRequest stripeRequest = StripeRequest.ofParams(chargeParams);
        // Save it in the payment
        final AddInterfaceInteraction request = stripeRequest.toInterfaceInteractionOfType("STRIPE_CHARGE_CREATE_REQUEST");
        final CompletableFuture<PaymentPair<StripeRequest>> createRequest = client
            .execute(PaymentUpdateCommand.of(payment, request))
            .toCompletableFuture()
            .thenApply(updatedPayment -> new PaymentPair(updatedPayment, stripeRequest));

        // Create the charge at Stripe.
        return createChargeFromRequest(createRequest);
    }


    private CompletableFuture<PaymentPair<Optional<String>>> createChargeFromRequest(CompletableFuture<PaymentPair<StripeRequest>> createRequest) {
        return createRequest
            .thenApply(pair -> pair.mapValue(stripeRequest -> {
                // Try to create the charge at Stripe.
                try {
                    return stripeRequest.toSuccess(
                        Charge.create(stripeRequest.getParams(), stripeRequest.getRequestOptions())
                    );
                } catch (StripeException e) {
                    return stripeRequest.<Charge>toException(e);
                }
            }))
            .<PaymentPair<Optional<String>>>thenCompose(paymentPair -> {
                final StripeExecution<Charge> stripeChargeExecution = paymentPair.getRight();
                // Add an interface interaction with success or error.
                List<UpdateAction<Payment>> updateAction = stripeChargeExecution
                    .map(stripeObject ->
                        Arrays.<UpdateAction<Payment>>asList(
                            interactionOfTypeWith("STRIPE_CHARGED", stripeChargeExecution.idempotencyKey, "chargeId", stripeObject.getId()),
                            AddTransaction.of(TransactionBuilder
                                .of(TransactionType.CHARGE, toAmount(stripeObject), toTime(stripeObject.getCreated()))
                                .interactionId(stripeObject.getId())
                                .build()),
                            SetAmountPaid.of(toAmount(stripeObject))
                        )
                    )
                    .orElseGet(() -> stripeChargeExecution.exceptionToUpdateActions());
                final Optional<String> stripeChargeId = stripeChargeExecution.toOptional().map(c -> c.getId());
                return client.execute(PaymentUpdateCommand.of(paymentPair.getPayment(), updateAction))
                    .thenApply(updatedPayment -> new PaymentPair(updatedPayment, stripeChargeId));
            });
    }

    private Map<String, Object> createChargeParams(MonetaryAmount money, boolean capture) {
        Map<String, Object> chargeParams = new HashMap<String, Object>();
        chargeParams.put("amount", money.query(MonetaryUtil.minorUnits()));
        chargeParams.put("currency", money.getCurrency().getCurrencyCode());
        chargeParams.put("customer", stripeCustomerId);
        chargeParams.put("capture", capture);
        return chargeParams;
    }

    private MonetaryAmount toAmount(Charge charge) {
        return MoneyImpl.ofCents(charge.getAmount(), charge.getCurrency().toUpperCase());
    }

    private ZonedDateTime toTime(Long unixTimestamp) {
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(unixTimestamp), ZoneId.systemDefault());
    }
}
