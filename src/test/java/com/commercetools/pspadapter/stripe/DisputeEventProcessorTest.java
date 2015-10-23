package com.commercetools.pspadapter.stripe;

import com.commercetools.pspadapter.stripe.util.JavaClientInstantiation;
import com.commercetools.pspadapter.stripe.webhookprocessors.DisputeEventProcessor;
import com.stripe.model.Event;
import io.sphere.sdk.payments.Payment;
import io.sphere.sdk.payments.Transaction;
import io.sphere.sdk.payments.TransactionType;
import io.sphere.sdk.payments.commands.PaymentUpdateCommand;
import io.sphere.sdk.payments.commands.updateactions.SetInterfaceId;
import io.sphere.sdk.payments.commands.updateactions.SetMethodInfoInterface;
import io.sphere.sdk.payments.queries.PaymentByIdGet;
import org.junit.Test;

import java.util.Arrays;
import java.util.UUID;

import static org.junit.Assert.*;

public class DisputeEventProcessorTest extends AbstractCTPStripeTest {
    private final String json = "{\n" +
        "  \"created\": 1326853478,\n" +
        "  \"livemode\": false,\n" +
        "  \"id\": \"evt_00000000000000\",\n" +
        "  \"type\": \"%s\",\n" +
        "  \"object\": \"event\",\n" +
        "  \"request\": null,\n" +
        "  \"pending_webhooks\": 1,\n" +
        "  \"api_version\": \"2015-10-01\",\n" +
        "  \"data\": {\n" +
        "    \"object\": {\n" +
        "      \"id\": \"dp_00000000000000\",\n" +
        "      \"object\": \"dispute\",\n" +
        "      \"amount\": 1000,\n" +
        "      \"balance_transactions\": [],\n" +
        "      \"charge\": \"%s\",\n" +
        "      \"created\": 1445620184,\n" +
        "      \"currency\": \"eur\",\n" +
        "      \"evidence\": {\n" +
        "        \"access_activity_log\": null,\n" +
        "        \"billing_address\": null,\n" +
        "        \"cancellation_policy\": null,\n" +
        "        \"cancellation_policy_disclosure\": null,\n" +
        "        \"cancellation_rebuttal\": null,\n" +
        "        \"customer_communication\": null,\n" +
        "        \"customer_email_address\": null,\n" +
        "        \"customer_name\": null,\n" +
        "        \"customer_purchase_ip\": null,\n" +
        "        \"customer_signature\": null,\n" +
        "        \"duplicate_charge_documentation\": null,\n" +
        "        \"duplicate_charge_explanation\": null,\n" +
        "        \"duplicate_charge_id\": null,\n" +
        "        \"product_description\": null,\n" +
        "        \"receipt\": null,\n" +
        "        \"refund_policy\": null,\n" +
        "        \"refund_policy_disclosure\": null,\n" +
        "        \"refund_refusal_explanation\": null,\n" +
        "        \"service_date\": null,\n" +
        "        \"service_documentation\": null,\n" +
        "        \"shipping_address\": null,\n" +
        "        \"shipping_carrier\": null,\n" +
        "        \"shipping_date\": null,\n" +
        "        \"shipping_documentation\": null,\n" +
        "        \"shipping_tracking_number\": null,\n" +
        "        \"uncategorized_file\": null,\n" +
        "        \"uncategorized_text\": null\n" +
        "      },\n" +
        "      \"evidence_details\": {\n" +
        "        \"due_by\": 1447286399,\n" +
        "        \"has_evidence\": false,\n" +
        "        \"past_due\": false,\n" +
        "        \"submission_count\": 0\n" +
        "      },\n" +
        "      \"is_charge_refundable\": false,\n" +
        "      \"livemode\": false,\n" +
        "      \"metadata\": {},\n" +
        "      \"reason\": \"general\",\n" +
        "      \"status\": \"%s\"\n" +
        "    }\n" +
        "  }\n" +
        "}";

    private final String needsResponse = "needs_response";
    private final String won = "won";
    private final String lost = "lost";

    @Test
    public void testUnknownDisputeEvent() throws Exception {
        final String fakeReq = String.format(json, "charge.dispute.created", "unknownChargeId", "needs_response");
        final Event event = Event.GSON.fromJson(fakeReq, Event.class);
        final DisputeEventProcessor disputeEventProcessor = new DisputeEventProcessor(new JavaClientInstantiation().instantiate());
        final int statusCode = disputeEventProcessor.processDisputeEvent(event);

        assertEquals(404, statusCode);
    }

    @Test
    public void testDisputeEvent() throws Exception {
        Payment beforePayment = createPayment();
        final String fakeChargeId = "ch_" + UUID.randomUUID().toString();
        client.execute(PaymentUpdateCommand.of(beforePayment,
            Arrays.asList(SetInterfaceId.of(fakeChargeId), SetMethodInfoInterface.of("STRIPE"))));

        // First event processing should change payment object
        final String fakeReq = String.format(json, "charge.dispute.created", fakeChargeId, "needs_response");
        final Event event = Event.GSON.fromJson(fakeReq, Event.class);
        final DisputeEventProcessor disputeEventProcessor = new DisputeEventProcessor(new JavaClientInstantiation().instantiate());
        final int statusCode = disputeEventProcessor.processDisputeEvent(event);

        assertEquals(201, statusCode);

        Payment payment1 = client.execute(PaymentByIdGet.of(beforePayment)).toCompletableFuture().get();
        assertEquals(2, payment1.getInterfaceInteractions().size());
        assertEquals(typeKeyToId.getId("STRIPE_DISPUTE_UPDATE").get(), payment1.getInterfaceInteractions().get(1).getType().getId());
        assertEquals("evt_00000000000000", payment1.getInterfaceInteractions().get(1).getFieldAsString("eventId"));
        assertTrue(payment1.getPaymentStatus().getInterfaceText().contains("needs_response"));

        // Second event processing should not change payment object
        final int statusCode2 = disputeEventProcessor.processDisputeEvent(event);

        assertEquals(200, statusCode2);

        Payment payment2 = client.execute(PaymentByIdGet.of(beforePayment)).toCompletableFuture().get();
        assertEquals(payment1.getVersion(), payment2.getVersion());
    }

    @Test
    public void testWonDisputeEvent() throws Exception {
        Payment beforePayment = createPayment();
        final String fakeChargeId = "ch_" + UUID.randomUUID().toString();
        client.execute(PaymentUpdateCommand.of(beforePayment,
            Arrays.asList(SetInterfaceId.of(fakeChargeId), SetMethodInfoInterface.of("STRIPE"))));

        // First event processing should change payment object
        final String fakeReq = String.format(json, "charge.dispute.closed", fakeChargeId, "won");
        final Event event = Event.GSON.fromJson(fakeReq, Event.class);
        final DisputeEventProcessor disputeEventProcessor = new DisputeEventProcessor(new JavaClientInstantiation().instantiate());
        final int statusCode = disputeEventProcessor.processDisputeEvent(event);

        assertEquals(201, statusCode);

        Payment payment1 = client.execute(PaymentByIdGet.of(beforePayment)).toCompletableFuture().get();
        assertEquals(typeKeyToId.getId("STRIPE_DISPUTE_UPDATE").get(), payment1.getInterfaceInteractions().get(1).getType().getId());
        assertEquals("evt_00000000000000", payment1.getInterfaceInteractions().get(1).getFieldAsString("eventId"));
        assertNull(payment1.getPaymentStatus().getInterfaceText());
    }

    @Test
    public void testLostDisputeEvent() throws Exception {
        Payment beforePayment = createPayment();
        final String fakeChargeId = "ch_" + UUID.randomUUID().toString();
        client.execute(PaymentUpdateCommand.of(beforePayment,
            Arrays.asList(SetInterfaceId.of(fakeChargeId), SetMethodInfoInterface.of("STRIPE"))));

        // First event processing should change payment object
        final String fakeReq = String.format(json, "charge.dispute.closed", fakeChargeId, "lost");
        final Event event = Event.GSON.fromJson(fakeReq, Event.class);
        final DisputeEventProcessor disputeEventProcessor = new DisputeEventProcessor(new JavaClientInstantiation().instantiate());
        final int statusCode = disputeEventProcessor.processDisputeEvent(event);

        assertEquals(201, statusCode);

        Payment payment1 = client.execute(PaymentByIdGet.of(beforePayment)).toCompletableFuture().get();
        assertEquals(typeKeyToId.getId("STRIPE_DISPUTE_UPDATE").get(), payment1.getInterfaceInteractions().get(1).getType().getId());
        assertEquals("evt_00000000000000", payment1.getInterfaceInteractions().get(1).getFieldAsString("eventId"));
        assertTrue(payment1.getPaymentStatus().getInterfaceText().contains("lost"));
        assertEquals(1, payment1.getTransactions().size());
        final Transaction transaction = payment1.getTransactions().get(0);
        assertEquals(TransactionType.CHARGEBACK, transaction.getType());
        assertEquals("evt_00000000000000", transaction.getInteractionId());
    }
}
