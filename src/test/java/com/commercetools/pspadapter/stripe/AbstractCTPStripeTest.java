package com.commercetools.pspadapter.stripe;

import com.commercetools.pspadapter.stripe.util.JavaClientInstantiation;
import com.stripe.Stripe;
import com.stripe.exception.*;
import com.stripe.model.Token;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.customers.Customer;
import io.sphere.sdk.models.DefaultCurrencyUnits;
import io.sphere.sdk.models.Reference;
import io.sphere.sdk.payments.Payment;
import io.sphere.sdk.payments.PaymentDraftBuilder;
import io.sphere.sdk.payments.commands.PaymentCreateCommand;
import io.sphere.sdk.types.CustomFieldsDraft;
import io.sphere.sdk.types.CustomFieldsDraftBuilder;
import org.javamoney.moneta.FastMoney;
import org.junit.Before;

import java.util.Arrays;

public abstract class AbstractCTPStripeTest {
    final SphereClient client = new JavaClientInstantiation().instantiate();
    final TypeKeyToId typeKeyToId = new TypeKeyToId(client);
    final Reference customer = Reference.of(Customer.referenceTypeId(), "444df5fa-ffb8-415e-9755-448d3ddce32f");

    @Before
    public void before() throws Exception {
        setStripeApiKey();
        WebHookReceiver.createTypes(client);
    }

    protected void setStripeApiKey() {
        Stripe.apiKey = System.getenv("CTP_STRIPE_ADAPTER_STRIPE_API_KEY");
    }

    protected Payment createPayment() throws CardException, APIException, AuthenticationException, InvalidRequestException, APIConnectionException, InterruptedException, java.util.concurrent.ExecutionException {
        final Token token = WebHookReceiver.createTestToken();
        final CustomFieldsDraft interaction = CustomFieldsDraftBuilder
                .ofTypeKey("STRIPE_TOKEN_RECEIVED")
                .addObject("token", token.getId())
                .build();
        final PaymentCreateCommand paymentCreateCommand = PaymentCreateCommand.of(
            PaymentDraftBuilder
                .of(FastMoney.of(23, DefaultCurrencyUnits.EUR))
                .customer(customer)
                .interfaceInteractions(Arrays.asList(interaction))
                .build()
        );
        return client.execute(paymentCreateCommand).toCompletableFuture().get();
    }
}
