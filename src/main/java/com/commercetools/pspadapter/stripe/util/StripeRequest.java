package com.commercetools.pspadapter.stripe.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.StripeException;
import com.stripe.net.RequestOptions;
import io.sphere.sdk.payments.commands.updateactions.AddInterfaceInteraction;
import io.sphere.sdk.types.CustomFields;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public class StripeRequest {
    private final Map<String, Object> params;
    private final String idempotencyKey;

    public StripeRequest(Map<String, Object> params, String idempotencyKey) {
        this.params = params;
        this.idempotencyKey = idempotencyKey;
    }

    public RequestOptions getRequestOptions() {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public AddInterfaceInteraction toInterfaceInteractionOfType(String typeKey) {
        ObjectMapper om = new ObjectMapper();
        HashMap<String, Object> objects = new HashMap();
        objects.put("idempotencyKey", idempotencyKey);
        try {
            objects.put("params", om.writeValueAsString(params));
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            throw new CompletionException(e);
        }
        return AddInterfaceInteraction.ofTypeKeyAndObjects(typeKey, objects);
    }

    public static StripeRequest ofParams(Map<String, Object> params) {
        return new StripeRequest(params, UUID.randomUUID().toString());
    }

    public static StripeRequest of(CustomFields interfaceInteraction) {
        ObjectMapper om = new ObjectMapper();
        try {
            return new StripeRequest(
                om.readValue(interfaceInteraction.getFieldAsString("params"), new TypeReference<Map<String, Object>>() {}),
                interfaceInteraction.getFieldAsString("idempotencyKey")
            );
        } catch (IOException e) {
            e.printStackTrace();
            throw new CompletionException("Can not load com.commercetools.pspadapter.stripe.util.StripeRequest from interfaceInteraction", e);
        }
    }

    public <T> StripeExecution<T> toSuccess(T stripeObject) {
        return StripeExecution.success(idempotencyKey, stripeObject);
    }

    public <T> StripeExecution<T> toException(StripeException e) {
        return StripeExecution.<T>exceptional(idempotencyKey, e);
    }
}
