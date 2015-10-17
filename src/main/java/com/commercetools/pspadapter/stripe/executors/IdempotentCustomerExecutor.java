package com.commercetools.pspadapter.stripe.executors;

import com.commercetools.pspadapter.stripe.TypeKeyToId;
import com.commercetools.pspadapter.stripe.util.PaymentPair;
import com.commercetools.pspadapter.stripe.util.StripeExecution;
import com.commercetools.pspadapter.stripe.util.StripeRequest;
import com.stripe.exception.StripeException;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.commands.UpdateAction;
import io.sphere.sdk.customers.Customer;
import io.sphere.sdk.customers.queries.CustomerByIdGet;
import io.sphere.sdk.payments.Payment;
import io.sphere.sdk.payments.commands.PaymentUpdateCommand;
import io.sphere.sdk.payments.commands.updateactions.AddInterfaceInteraction;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class IdempotentCustomerExecutor extends PaymentHelperMethods implements IdempotentStripeRequestExecutor<Optional<String>> {
    final private Payment payment;
    final private SphereClient client;

    public IdempotentCustomerExecutor(Payment payment, SphereClient client) {
        super(new TypeKeyToId(client));
        this.payment = payment;
        this.client = client;
    }

    @Override
    public Optional<PaymentPair<Optional<String>>> previousExecution() {
        // Idempotency check: Has the customer already been added?
        final Optional<PaymentPair<Optional<String>>> successfulExecution =
            getLastInteractionOfType(payment, "STRIPE_CUSTOMER_CHECKED")
                .map(interaction ->
                    new PaymentPair<Optional<String>>(payment, Optional.of(interaction.getFieldAsString("stripeCustomerId")))
                );
        if (successfulExecution.isPresent()) return successfulExecution;
        // Idempotency check: Has the customer creation failed permanently?
        else return
            getLastInteractionOfType(payment, "STRIPE_CUSTOMER_CREATE_REQUEST")
                // There was a previous request, search for exception with same idempotencyKey.
                .flatMap(interaction -> getLastException(payment, interaction)
                        .map(exception ->
                                new PaymentPair<Optional<String>>(payment, Optional.<String>empty())
                        )
                );
    }

    @Override
    public Optional<CompletableFuture<PaymentPair<Optional<String>>>> retryPreviousExecution() {
        // Idempotency check: Did we already try to create the customer?
        return getLastInteractionOfType(payment, "STRIPE_CUSTOMER_CREATE_REQUEST")
            .map(interaction ->
                // Retry the same request
                createCustomerFromRequest(CompletableFuture.completedFuture(
                    new PaymentPair(payment, StripeRequest.of(interaction))
                ))
            );
    }

    @Override
    public CompletableFuture<PaymentPair<Optional<String>>> newExecution() {
        // Check if we have a token.
        return getToken(payment)
            .map(token ->
                // Create the customer at Stripe
                createCustomer(payment, token)
            )
            .orElse(CompletableFuture.completedFuture(
                new PaymentPair<Optional<String>>(payment, Optional.empty()))
            );
    }

    private CompletableFuture<PaymentPair<Optional<String>>> createCustomer(Payment payment, String token) {
        // Create the request and save it in the payment.
        final CompletableFuture<PaymentPair<StripeRequest>> createRequest = Optional.ofNullable(payment.getCustomer())
            .map(customerReference ->
                    // Create request with CTP customer data
                    client
                        .execute(CustomerByIdGet.of(customerReference)).toCompletableFuture()
                        .thenApply(customer -> StripeRequest.ofParams(createCustomerParams(customer, token)))
            )
            .orElseGet(() ->
                    // Create request for anonymous customer
                    CompletableFuture.completedFuture(StripeRequest.ofParams(createAnonymousCustomerParams(token)))
            )
            .<PaymentPair<StripeRequest>>thenCompose(stripeRequest -> {
                // Save the request in the payment
                final AddInterfaceInteraction request = stripeRequest.toInterfaceInteractionOfType("STRIPE_CUSTOMER_CREATE_REQUEST");
                return client
                    .execute(PaymentUpdateCommand.of(payment, request))
                    .thenApply(updatedPayment -> new PaymentPair(updatedPayment, stripeRequest));
            });

        // Create the customer at Stripe.
        return createCustomerFromRequest(createRequest);
    }

    private CompletableFuture<PaymentPair<Optional<String>>> createCustomerFromRequest(CompletableFuture<PaymentPair<StripeRequest>> createRequest) {
        return createRequest
            .thenApply(pair -> pair.mapValue(stripeRequest -> {
                // Try to create the customer at Stripe.
                try {
                    return stripeRequest.toSuccess(
                        com.stripe.model.Customer.create(stripeRequest.getParams(), stripeRequest.getRequestOptions())
                    );
                } catch (StripeException e) {
                    return stripeRequest.<com.stripe.model.Customer>toException(e);
                }
            }))
            .<PaymentPair<Optional<String>>>thenCompose(paymentPair -> {
                final StripeExecution<com.stripe.model.Customer> stripeCustomerExecution = paymentPair.getRight();
                // Add an interface interaction with success or error.
                List<UpdateAction<Payment>> updateAction = stripeCustomerExecution
                    .map(stripeObject ->
                        Arrays.<UpdateAction<Payment>>asList(
                            interactionOfTypeWith("STRIPE_CUSTOMER_CHECKED", stripeCustomerExecution.idempotencyKey, "stripeCustomerId", stripeObject.getId())
                        )
                    )
                    .orElseGet(() -> stripeCustomerExecution.exceptionToUpdateActions());
                final Optional<String> stripeCustomerId = stripeCustomerExecution.toOptional().map(c -> c.getId());
                return client.execute(PaymentUpdateCommand.of(paymentPair.getPayment(), updateAction))
                    .thenApply(updatedPayment -> new PaymentPair(updatedPayment, stripeCustomerId));
            });
    }

    private Map<String, Object> createAnonymousCustomerParams(String tokenId) {
        Map<String, Object> customerParams = new HashMap<String, Object>();
        customerParams.put("source", tokenId);
        return customerParams;
    }

    private Map<String, Object> createCustomerParams(Customer customer, String tokenId) {
        Map<String, Object> customerParams = new HashMap<String, Object>();
        customerParams.put("source", tokenId);
        customerParams.put("email", customer.getEmail());
        Map<String, Object> metadata = createCustomerMetadata(customer);
        customerParams.put("metadata", metadata);
        return customerParams;
    }

    private Map<String, Object> createCustomerMetadata(Customer customer) {
        Map<String, Object> metadata = new HashMap<String, Object>();
        metadata.put("ctp_id", customer.getId());
        metadata.put("firstName", customer.getFirstName());
        metadata.put("lastName", customer.getLastName());
        metadata.put("ctp_version", customer.getVersion());
        return metadata;
    }
}
