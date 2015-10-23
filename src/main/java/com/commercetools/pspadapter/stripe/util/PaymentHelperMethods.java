package com.commercetools.pspadapter.stripe.util;

import com.commercetools.pspadapter.stripe.TypeKeyToId;
import com.stripe.model.Charge;
import com.stripe.model.Dispute;
import io.sphere.sdk.payments.Payment;
import io.sphere.sdk.payments.commands.updateactions.AddInterfaceInteraction;
import io.sphere.sdk.types.CustomFields;
import io.sphere.sdk.utils.MoneyImpl;

import javax.money.MonetaryAmount;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Optional;
import java.util.stream.Stream;

public abstract class PaymentHelperMethods {
    final protected TypeKeyToId typeKeyToId;

    public PaymentHelperMethods(TypeKeyToId typeKeyToId) {
        this.typeKeyToId = typeKeyToId;
    }

    protected Optional<Stream<CustomFields>> getInteractionsOfType(Payment payment, String typeKey) {
        return typeKeyToId
            .getId(typeKey)
            .map(typeId ->
                payment.getInterfaceInteractions().stream()
                    .filter(interaction -> interaction.getType().getId().equals(typeId))
            );
    }

    protected Optional<CustomFields> getFirstInteractionOfType(Payment payment, String typeKey) {
        return getInteractionsOfType(payment, typeKey).flatMap(s -> s.findFirst());
    }

    protected Optional<CustomFields> getLastInteractionOfType(Payment payment, String typeKey) {
        return getInteractionsOfType(payment, typeKey).flatMap(s -> s.reduce((a, b) -> b)); // == findLast()
    }

    protected Optional<CustomFields> getLastInteractionOfTypeWithField(Payment payment, String typeKey, String fieldName, String fieldContent) {
        return getInteractionsOfType(payment, typeKey)
            .map(s -> s.filter(interaction -> interaction.getFieldAsString(fieldName).equals(fieldContent)))
            .flatMap(s -> s.reduce((a, b) -> b)); // == findLast()
    }

    protected Optional<CustomFields> getLastException(Payment payment, CustomFields interaction) {
        return Optional.ofNullable(interaction.getFieldAsString("idempotencyKey"))
            .flatMap(idempotencyKey ->
                    getLastInteractionOfTypeWithField(payment, "STRIPE_EXCEPTION", "idempotencyKey", idempotencyKey)
            );
    }

    protected Optional<String> getToken(Payment payment) {
        final Optional<CustomFields> stripe_token_received = getLastInteractionOfType(payment, "STRIPE_TOKEN_RECEIVED");
        final Optional<String> token = stripe_token_received
                .flatMap(tokenReceived -> Optional.ofNullable(tokenReceived.getFieldAsString("token")));
        return token;
    }

    protected AddInterfaceInteraction interactionOfTypeWith(String typeKey, String idempotencyKey, String fieldName, String fieldValue) {
        HashMap<String, Object> objects = new HashMap();
        objects.put("idempotencyKey", idempotencyKey);
        objects.put(fieldName, fieldValue);
        return AddInterfaceInteraction.ofTypeKeyAndObjects(typeKey, objects);
    }

    protected MonetaryAmount toAmount(Charge charge) {
        return getMonetaryAmount(charge.getAmount(), charge.getCurrency());
    }

    protected MonetaryAmount toAmount(Dispute dispute) {
        return getMonetaryAmount(dispute.getAmount(), dispute.getCurrency());
    }

    private MonetaryAmount getMonetaryAmount(Integer amount, String currency) {
        return MoneyImpl.ofCents(amount, currency.toUpperCase());
    }

    protected ZonedDateTime toTime(Long unixTimestamp) {
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(unixTimestamp), ZoneId.systemDefault());
    }
}
