package com.commercetools.pspadapter.stripe.executors;

import com.commercetools.pspadapter.stripe.util.PaymentPair;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface IdempotentStripeRequestExecutor <T> {
    /**
     * Checks for a previous execution that did not fail with a temporary error (e.g. Network Timeout).
     * @return The result of a previous execution, if there was any.
     */
    Optional<PaymentPair<T>> previousExecution();

    /**
     * @return If there was a previous execution that did not succeed, the result of the retry is returned.
     */
    Optional<CompletableFuture<PaymentPair<T>>> retryPreviousExecution();

    /**
     * @return Executes a new request.
     */
    CompletableFuture<PaymentPair<T>> newExecution();

    /**
     * Checks whether the request has already been completed, if so that result is returned (without hitting the Stripe API).
     * If not, checks whether the request has been tried, but did neither succeed nor fail permanently. If so, it will execute the same request again.
     * If no request has been attempted before, the requested is created and executed.
     *
     * @return Returns the result of the request execution.
     */
    default CompletableFuture<PaymentPair<T>> executionResult() {
        return previousExecution()
                .map(t -> CompletableFuture.completedFuture(t))
                .orElseGet(() ->
                    retryPreviousExecution()
                        .orElseGet(() ->
                            newExecution()
                        )
                );
    }
}
