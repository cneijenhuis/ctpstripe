package com.commercetools.pspadapter.stripe;

import com.commercetools.pspadapter.stripe.util.JavaClientInstantiation;
import com.commercetools.pspadapter.stripe.webhookprocessors.DisputeEventProcessor;
import com.stripe.Stripe;
import com.stripe.exception.*;
import com.stripe.model.*;
import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.customers.Customer;
import io.sphere.sdk.models.DefaultCurrencyUnits;
import io.sphere.sdk.models.LocalizedString;
import io.sphere.sdk.models.Reference;
import io.sphere.sdk.models.TextInputHint;
import io.sphere.sdk.payments.Payment;
import io.sphere.sdk.payments.PaymentDraftBuilder;
import io.sphere.sdk.payments.commands.PaymentCreateCommand;
import io.sphere.sdk.payments.queries.PaymentQuery;
import io.sphere.sdk.payments.queries.PaymentQueryModel;
import io.sphere.sdk.queries.PagedQueryResult;
import io.sphere.sdk.types.*;
import io.sphere.sdk.types.commands.TypeCreateCommand;
import org.javamoney.moneta.FastMoney;

import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static spark.Spark.get;
import static spark.Spark.halt;
import static spark.Spark.post;

public class WebHookReceiver {
    public static void main(String[] args) throws Exception {
        get("/test", (req, res) -> {
            testing();
            return "Cool";
        });

        post("/stripe/event", (req, res) -> {
            // Parse req to Stripe Event
            Event webhookEvent = Event.GSON.fromJson(req.body(), Event.class);
            if (webhookEvent.getType().startsWith("charge.dispute")) {
                final DisputeEventProcessor disputeEventProcessor = new DisputeEventProcessor(new JavaClientInstantiation().instantiate());
                final int statusCode = disputeEventProcessor.processDisputeEvent(confirmEvent(webhookEvent));
                halt(statusCode);
            }
            else {
                // We're not interested in this event
                System.out.println("Ignored event of type: " + webhookEvent.getType());
                halt(200);
            }

            return null;
        });
    }

    private static Event confirmEvent(Event webhookEvent) throws StripeException {
        // To make sure that this is not a spoofed webhook, fetch the Event again directly from the Stripe API.
        return Event.retrieve(webhookEvent.getId());
    }

    private static void testing() throws Exception {
        final SphereClient client = new JavaClientInstantiation().instantiate();
        Stripe.apiKey = System.getenv("CTP_STRIPE_ADAPTER_STRIPE_API_KEY");

        createTypes(client);

        final Token token = createTestToken();
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
        client.execute(paymentCreateCommand).toCompletableFuture().get();

        final CTPMessagePull ctpMessagePull = new CTPMessagePull(client);
        final PaymentCreationListener pcl = new PaymentCreationListener(client);
        ctpMessagePull.pullPaymentCreated(3l).forEach(msg -> {
            try {
                pcl.paymentCreated(msg);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        client.close();
    }

    public static void createTypes(SphereClient client) throws Exception {
        final TypeKeyToId typeKeyToId = new TypeKeyToId(client);
        createTypeWithStringField(client, typeKeyToId, "STRIPE_TOKEN_RECEIVED", "token");
        createRequestType(client, typeKeyToId, "STRIPE_CUSTOMER_CREATE_REQUEST");
        createRequestType(client, typeKeyToId, "STRIPE_CHARGE_CREATE_REQUEST");
        createTypeWithTwoStringFields(client, typeKeyToId, "STRIPE_EXCEPTION", "response", "idempotencyKey");
        createTypeWithTwoStringFields(client, typeKeyToId, "STRIPE_TEMPORARY_EXCEPTION", "response", "idempotencyKey");
        createTypeWithTwoStringFields(client, typeKeyToId, "STRIPE_CUSTOMER_CHECKED", "stripeCustomerId", "idempotencyKey");
        createTypeWithTwoStringFields(client, typeKeyToId, "STRIPE_CHARGED", "chargeId", "idempotencyKey");
    }

    private static void createTypeWithStringField(SphereClient client, TypeKeyToId typeKeyToId, String typeKey, String fieldName) throws InterruptedException, java.util.concurrent.ExecutionException {
        if (!typeKeyToId.getId(typeKey).isPresent()) {
            final FieldDefinition stringFieldDefinition = createFieldDefinition(fieldName);
            final List<FieldDefinition> fieldDefinitions = singletonList(stringFieldDefinition);
            createType(client, typeKey, fieldDefinitions);
        }
    }

    private static void createRequestType(SphereClient client, TypeKeyToId typeKeyToId, String typeKey) throws InterruptedException, java.util.concurrent.ExecutionException {
        createTypeWithTwoStringFields(client, typeKeyToId, typeKey, "params", "idempotencyKey");
    }

    private static void createTypeWithTwoStringFields(SphereClient client, TypeKeyToId typeKeyToId, String typeKey, String field1, String field2) throws InterruptedException, java.util.concurrent.ExecutionException {
        if (!typeKeyToId.getId(typeKey).isPresent()) {
            final FieldDefinition field1Def = createFieldDefinition(field1);
            final FieldDefinition field2Def = createFieldDefinition(field2);
            createType(client, typeKey, Arrays.asList(field1Def, field2Def));
        }
    }

    private static FieldDefinition createFieldDefinition(String fieldName) {
        return FieldDefinition.of(StringType.of(), fieldName, LocalizedString.of(Locale.ENGLISH, fieldName), false, TextInputHint.SINGLE_LINE);
    }

    private static void createType(SphereClient client, String typeKey, List<FieldDefinition> fieldDefinitions) throws InterruptedException, java.util.concurrent.ExecutionException {
        final TypeDraft typeDraft = TypeDraftBuilder.of(typeKey, LocalizedString.of(Locale.ENGLISH, typeKey), singleton("payment-interface-interaction"))
                .fieldDefinitions(fieldDefinitions)
                .build();
        final Type type = client.execute(TypeCreateCommand.of(typeDraft)).toCompletableFuture().get();
    }

    public static Token createTestToken() throws CardException, APIException, AuthenticationException, InvalidRequestException, APIConnectionException {
        Map<String, Object> tokenParams = new HashMap<String, Object>();
        Map<String, Object> cardParams = new HashMap<String, Object>();
        cardParams.put("number", "4242424242424242");
        cardParams.put("exp_month", 10);
        cardParams.put("exp_year", 2016);
        cardParams.put("cvc", "314");
        tokenParams.put("card", cardParams);

        return Token.create(tokenParams);
    }
}