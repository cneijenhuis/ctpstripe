package com.commercetools.pspadapter.stripe;

import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.messages.queries.MessageQuery;
import io.sphere.sdk.payments.messages.PaymentCreatedMessage;

import java.util.List;
import java.util.concurrent.ExecutionException;

public class CTPMessagePull {
    final private SphereClient client;

    public CTPMessagePull(SphereClient client) {
        this.client = client;
    }

    public List<PaymentCreatedMessage> pullPaymentCreated(Long limit) throws ExecutionException, InterruptedException {
        return client
            .execute(
                MessageQuery.of()
                    .withSort(m -> m.createdAt().sort().desc())
                    .withExpansionPaths(m -> m.resource())
                    .withLimit(limit)
                    .forMessageType(PaymentCreatedMessage.MESSAGE_HINT)
            )
            .toCompletableFuture()
            .get()
            .getResults();
    }
}
