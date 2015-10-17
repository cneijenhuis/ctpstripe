package com.commercetools.pspadapter.stripe;

import com.commercetools.pspadapter.stripe.*;
import com.commercetools.pspadapter.stripe.executors.IdempotentCustomerExecutor;
import com.commercetools.pspadapter.stripe.util.JavaClientInstantiation;
import com.commercetools.pspadapter.stripe.util.PaymentPair;
import com.stripe.Stripe;
import com.stripe.model.Card;
import com.stripe.model.ExternalAccount;
import com.stripe.model.Token;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.customers.Customer;
import io.sphere.sdk.models.DefaultCurrencyUnits;
import io.sphere.sdk.models.Reference;
import io.sphere.sdk.payments.Payment;
import io.sphere.sdk.payments.PaymentDraftBuilder;
import io.sphere.sdk.payments.commands.PaymentCreateCommand;
import io.sphere.sdk.types.CustomFields;
import io.sphere.sdk.types.CustomFieldsDraft;
import io.sphere.sdk.types.CustomFieldsDraftBuilder;
import org.javamoney.moneta.FastMoney;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class IdempotentCustomerExecutorTest {
    final SphereClient client = new JavaClientInstantiation().instantiate();
    final TypeKeyToId typeKeyToId = new TypeKeyToId(client);

    @Before
    public void before() throws Exception {
        setStripeApiKey();
        WebHookReceiver.createTypes(client);
    }

    private void setStripeApiKey() {
        Stripe.apiKey = System.getenv("CTP_STRIPE_ADAPTER_STRIPE_API_KEY");
    }

    @Test
    public void testCustomerCreation() throws Exception {
        final Token token = WebHookReceiver.createTestToken();
        final CustomFieldsDraft interaction = CustomFieldsDraftBuilder
                .ofTypeKey("STRIPE_TOKEN_RECEIVED")
                .addObject("token", token.getId())
                .build();
        final Reference customer = Reference.of(Customer.referenceTypeId(), "444df5fa-ffb8-415e-9755-448d3ddce32f");
        final PaymentCreateCommand paymentCreateCommand = PaymentCreateCommand.of(
            PaymentDraftBuilder
                .of(FastMoney.of(23, DefaultCurrencyUnits.EUR))
                .customer(customer)
                .interfaceInteractions(Arrays.asList(interaction))
                .build()
        );
        Payment beforePayment = client.execute(paymentCreateCommand).toCompletableFuture().get();

        final IdempotentCustomerExecutor customerCreator = new IdempotentCustomerExecutor(beforePayment, client);
        assertFalse(customerCreator.previousExecution().isPresent());

        final PaymentPair<Optional<String>> paymentPair = customerCreator.executionResult().get();
        Payment payment = paymentPair.getPayment();

        assertEquals(3, payment.getInterfaceInteractions().size());
        final CustomFields firstInteraction = payment.getInterfaceInteractions().get(1);
        assertEquals(typeKeyToId.getId("STRIPE_CUSTOMER_CREATE_REQUEST").get(), firstInteraction.getType().getId());

        final CustomFields secondInteraction = payment.getInterfaceInteractions().get(2);
        assertEquals(typeKeyToId.getId("STRIPE_CUSTOMER_CHECKED").get(), secondInteraction.getType().getId());
        assertEquals(firstInteraction.getFieldAsString("idempotencyKey"), secondInteraction.getFieldAsString("idempotencyKey"));

        String customerId = paymentPair.getRight().get();
        final com.stripe.model.Customer stripeCustomer = com.stripe.model.Customer.retrieve(customerId);
        assertNotNull(stripeCustomer);
        final List<ExternalAccount> data = stripeCustomer.getSources().getData();
        assertEquals(1, data.size());
        Card card = (Card) data.get(0);
        assertEquals("4242", card.getLast4());
        assertEquals(customer.getId(), stripeCustomer.getMetadata().get("ctp_id"));

        final IdempotentCustomerExecutor customerCreatorNew = new IdempotentCustomerExecutor(payment, client);
        assertTrue(customerCreatorNew.previousExecution().isPresent());
        assertEquals(customerId, customerCreatorNew.previousExecution().get().getRight().get());
    }

    @Test
    public void testFailingCustomerCreation() throws Exception {
        final CustomFieldsDraft interaction = CustomFieldsDraftBuilder
            .ofTypeKey("STRIPE_TOKEN_RECEIVED")
            .addObject("token", "tk_invalid")
            .build();
        final Reference customer = Reference.of(Customer.referenceTypeId(), "444df5fa-ffb8-415e-9755-448d3ddce32f");
        final PaymentCreateCommand paymentCreateCommand = PaymentCreateCommand.of(
            PaymentDraftBuilder
                .of(FastMoney.of(23, DefaultCurrencyUnits.EUR))
                .customer(customer)
                .interfaceInteractions(Arrays.asList(interaction))
                .build()
        );
        Payment beforePayment = client.execute(paymentCreateCommand).toCompletableFuture().get();

        final IdempotentCustomerExecutor customerCreator = new IdempotentCustomerExecutor(beforePayment, client);

        final PaymentPair<Optional<String>> paymentPair = customerCreator.executionResult().get();
        Payment payment = paymentPair.getPayment();

        assertEquals(3, payment.getInterfaceInteractions().size());
        final CustomFields firstInteraction = payment.getInterfaceInteractions().get(1);
        assertEquals(typeKeyToId.getId("STRIPE_CUSTOMER_CREATE_REQUEST").get(), firstInteraction.getType().getId());

        final CustomFields secondInteraction = payment.getInterfaceInteractions().get(2);
        assertEquals(typeKeyToId.getId("STRIPE_EXCEPTION").get(), secondInteraction.getType().getId());
        assertEquals(firstInteraction.getFieldAsString("idempotencyKey"), secondInteraction.getFieldAsString("idempotencyKey"));
        assertTrue(secondInteraction.getFieldAsString("response").contains("No such token"));

        final IdempotentCustomerExecutor customerCreatorNew = new IdempotentCustomerExecutor(payment, client);
        assertTrue(customerCreatorNew.previousExecution().isPresent());
        assertFalse(customerCreatorNew.previousExecution().get().getRight().isPresent());
    }

    @Test
    public void testTemporarilyFailingCustomerCreation() throws Exception {
        final Token token = WebHookReceiver.createTestToken();
        final CustomFieldsDraft interaction = CustomFieldsDraftBuilder
                .ofTypeKey("STRIPE_TOKEN_RECEIVED")
                .addObject("token", token.getId())
                .build();
        final Reference customer = Reference.of(Customer.referenceTypeId(), "444df5fa-ffb8-415e-9755-448d3ddce32f");
        final PaymentCreateCommand paymentCreateCommand = PaymentCreateCommand.of(
            PaymentDraftBuilder
                .of(FastMoney.of(23, DefaultCurrencyUnits.EUR))
                .customer(customer)
                .interfaceInteractions(Arrays.asList(interaction))
                .build()
        );
        Payment beforePayment = client.execute(paymentCreateCommand).toCompletableFuture().get();

        // Set invalid Stripe key to make request fail temporarily
        Stripe.apiKey = "invalid";

        final IdempotentCustomerExecutor customerCreator = new IdempotentCustomerExecutor(beforePayment, client);
        assertFalse(customerCreator.previousExecution().isPresent());

        final PaymentPair<Optional<String>> paymentPair = customerCreator.executionResult().get();
        Payment payment = paymentPair.getPayment();

        assertEquals(3, payment.getInterfaceInteractions().size());
        final CustomFields firstInteraction = payment.getInterfaceInteractions().get(1);
        assertEquals(typeKeyToId.getId("STRIPE_CUSTOMER_CREATE_REQUEST").get(), firstInteraction.getType().getId());

        final CustomFields secondInteraction = payment.getInterfaceInteractions().get(2);
        assertEquals(typeKeyToId.getId("STRIPE_TEMPORARY_EXCEPTION").get(), secondInteraction.getType().getId());
        assertEquals(firstInteraction.getFieldAsString("idempotencyKey"), secondInteraction.getFieldAsString("idempotencyKey"));
        assertTrue(secondInteraction.getFieldAsString("response").contains("Invalid API Key"));

        // Set valid Stripe key again
        setStripeApiKey();

        // Retry request
        final IdempotentCustomerExecutor secondCustomerCreator = new IdempotentCustomerExecutor(payment, client);
        assertFalse(secondCustomerCreator.previousExecution().isPresent());

        final PaymentPair<Optional<String>> secondPaymentPair = secondCustomerCreator.executionResult().get();
        Payment secondPayment = secondPaymentPair.getPayment();

        assertEquals(4, secondPayment.getInterfaceInteractions().size());
        final CustomFields thirdInteraction = secondPayment.getInterfaceInteractions().get(3);
        assertEquals(typeKeyToId.getId("STRIPE_CUSTOMER_CHECKED").get(), thirdInteraction.getType().getId());
        assertEquals(firstInteraction.getFieldAsString("idempotencyKey"), thirdInteraction.getFieldAsString("idempotencyKey"));
    }

}
