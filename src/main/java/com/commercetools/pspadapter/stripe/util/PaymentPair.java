package com.commercetools.pspadapter.stripe.util;

import io.sphere.sdk.payments.Payment;
import org.apache.commons.lang3.tuple.Pair;

import java.util.function.Function;

public class PaymentPair<R> extends Pair<Payment, R> {
    public final Payment payment;
    public final R right;

    public static <R> PaymentPair<R> of(Payment payment, R right) {
        return new PaymentPair(payment, right);
    }

    public PaymentPair(Payment payment, R right) {
        this.payment = payment;
        this.right = right;
    }

    public Payment getLeft() {
        return this.payment;
    }

    public Payment getPayment() {
        return payment;
    }

    public R getRight() {
        return this.right;
    }

    public R setValue(R value) {
        // Immutable!
        throw new UnsupportedOperationException();
    }

    public <T> PaymentPair<T> mapValue(Function<R, T> f) {
       return new PaymentPair<T>(payment, f.apply(right));
    }
}
