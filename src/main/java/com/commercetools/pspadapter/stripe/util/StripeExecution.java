package com.commercetools.pspadapter.stripe.util;

import com.stripe.exception.*;
import io.sphere.sdk.commands.UpdateAction;
import io.sphere.sdk.payments.Payment;
import io.sphere.sdk.payments.commands.updateactions.AddInterfaceInteraction;
import io.sphere.sdk.payments.commands.updateactions.SetStatusInterfaceCode;
import io.sphere.sdk.payments.commands.updateactions.SetStatusInterfaceText;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

public class StripeExecution<T> {
    public final String idempotencyKey;
    public final T stripeObject;
    public final StripeException stripeException;

    public static <T> StripeExecution<T> success(String idempotencyKey, T stripeObject) {
        return new StripeExecution<T>(idempotencyKey, stripeObject, null);
    }

    public static <T> StripeExecution<T> exceptional(String idempotencyKey, StripeException e) {
        e.printStackTrace();
        return new StripeExecution<T>(idempotencyKey, null, e);
    }

    private StripeExecution(String idempotencyKey, T stripeObject, StripeException stripeException) {
        this.idempotencyKey = idempotencyKey;
        this.stripeObject = stripeObject;
        this.stripeException = stripeException;
    }

    public boolean isSuccess() {
        return stripeException == null;
    }

    public Optional<T> toOptional() {
        return Optional.ofNullable(stripeObject);
    }

    public<U> StripeExecution<U> map(Function<? super T, ? extends U> mapper) {
        Objects.requireNonNull(mapper);
        if (!isSuccess())
            return (StripeExecution<U>) this;
        else {
            return StripeExecution.success(idempotencyKey, mapper.apply(stripeObject));
        }
    }

    public T orElseHandleException(Function<Exception, T> mapper) {
        Objects.requireNonNull(mapper);
        if (!isSuccess())
            return mapper.apply(stripeException);
        else {
            return stripeObject;
        }
    }

    public T orElseGet(Supplier<T> supplier) {
        Objects.requireNonNull(supplier);
        if (!isSuccess())
            return supplier.get();
        else {
            return stripeObject;
        }
    }

    public List<UpdateAction<Payment>> exceptionToUpdateActions() {
        assert(stripeException != null);
        return Arrays.asList(
            exceptionToInterfaceInteraction(),
            exceptionToStatusInterfaceText(),
            exceptionToStatusInterfaceCode()
        );
    }

    private AddInterfaceInteraction exceptionToInterfaceInteraction() {
        HashMap<String, Object> objects = new HashMap();
        objects.put("idempotencyKey", idempotencyKey);
        objects.put("response", stripeException.toString());
        if (stripeException instanceof APIConnectionException ||
            stripeException instanceof APIException ||
            stripeException instanceof AuthenticationException ||
            stripeException instanceof RateLimitException)
            return AddInterfaceInteraction.ofTypeKeyAndObjects("STRIPE_TEMPORARY_EXCEPTION", objects);
        else  return AddInterfaceInteraction.ofTypeKeyAndObjects("STRIPE_EXCEPTION", objects);
    }

    private SetStatusInterfaceText exceptionToStatusInterfaceText() {
        return SetStatusInterfaceText.of(stripeException.getMessage());
    }

    private SetStatusInterfaceCode exceptionToStatusInterfaceCode() {
        return SetStatusInterfaceCode.of(stripeException.getStatusCode().toString());
    }
}
