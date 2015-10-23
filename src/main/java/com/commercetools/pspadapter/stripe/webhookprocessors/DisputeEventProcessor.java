package com.commercetools.pspadapter.stripe.webhookprocessors;

import com.commercetools.pspadapter.stripe.TypeKeyToId;
import com.commercetools.pspadapter.stripe.util.JavaClientInstantiation;
import com.commercetools.pspadapter.stripe.util.PaymentHelperMethods;
import com.stripe.model.Dispute;
import com.stripe.model.Event;
import com.stripe.model.EventData;
import com.stripe.model.StripeObject;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.commands.UpdateAction;
import io.sphere.sdk.payments.Payment;
import io.sphere.sdk.payments.TransactionBuilder;
import io.sphere.sdk.payments.TransactionType;
import io.sphere.sdk.payments.commands.PaymentUpdateCommand;
import io.sphere.sdk.payments.commands.updateactions.AddInterfaceInteraction;
import io.sphere.sdk.payments.commands.updateactions.AddTransaction;
import io.sphere.sdk.payments.commands.updateactions.SetAmountRefunded;
import io.sphere.sdk.payments.commands.updateactions.SetStatusInterfaceText;
import io.sphere.sdk.payments.queries.PaymentQuery;
import io.sphere.sdk.payments.queries.PaymentQueryModel;

import javax.money.MonetaryAmount;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

public class DisputeEventProcessor extends PaymentHelperMethods {
    final private SphereClient client;

    public DisputeEventProcessor(SphereClient client) {
        super(new TypeKeyToId(client));
        this.client = client;
    }

    public int processDisputeEvent(Event event) throws ExecutionException, InterruptedException {
        final StripeObject stripeObject = event.getData().getObject();
        if (stripeObject instanceof Dispute) {
            Dispute dispute = (Dispute) stripeObject;
            // Search for corresponding Payment Object
            final String chargeId = dispute.getCharge();
            return client
                .execute(
                    PaymentQuery
                        .of()
                        .withPredicates(
                            PaymentQueryModel.of().interfaceId().is(chargeId)
                                .and(PaymentQueryModel.of().paymentMethodInfo().paymentInterface().is("STRIPE"))
                        )
                        .withLimit(1)
                )
                .toCompletableFuture()
                .thenCompose(r -> r.head().map(payment -> {
                    // Idempotency check: Did we process this event before?
                    if (getLastInteractionOfTypeWithField(payment, "STRIPE_DISPUTE_UPDATE", "eventId", event.getId()).isPresent()) {
                        return CompletableFuture.completedFuture(200);
                    } else {
                        // Add the event to the payment object
                        return addEventToPayment(payment, event, dispute)
                            .thenApply(p -> 201);
                    }
                })
                // The corresponding payment object was not found
                .orElseGet(() -> CompletableFuture.completedFuture(404)))
                .get();
        } else {
            System.out.println("Expected object of type Dispute in Event, but got something else!");
            return 400;
        }
    }

    private CompletableFuture<Payment> addEventToPayment(Payment payment, Event event, Dispute dispute) {
        HashMap<String, Object> objects = new HashMap();
        objects.put("eventId", event.getId());
        objects.put("dispute", dispute.toString());
        final List<UpdateAction<Payment>> updateActions = Arrays.<UpdateAction<Payment>>asList(
            // Save full dispute object
            AddInterfaceInteraction.ofTypeKeyAndObjects("STRIPE_DISPUTE_UPDATE", objects)
        );
        // Set status interface text to the dispute status and dispute reason
        final String statusText = "Dispute! Status: " + dispute.getStatus() + " Reason: " + dispute.getReason();
        if (!statusText.equals(payment.getPaymentStatus().getInterfaceText())) {
            updateActions.add(SetStatusInterfaceText.of(statusText));
        }
        if (event.getType().equals("charge.dispute.closed")) {
            if (dispute.getStatus().equals("lost")) {
                // Add transaction of the chargeback
                final MonetaryAmount refundAmount = toAmount(dispute);
                updateActions.add(
                    AddTransaction.of(
                        TransactionBuilder
                            .of(TransactionType.CHARGEBACK, refundAmount, toTime(event.getCreated()))
                            .interactionId(event.getId())
                            .build()
                    )
                );
            }
            else {
                // Remove the status interface text
                updateActions.add(SetStatusInterfaceText.of(null));
            }
        }
        return client.execute(PaymentUpdateCommand.of(payment, updateActions)).toCompletableFuture();
    }
}
