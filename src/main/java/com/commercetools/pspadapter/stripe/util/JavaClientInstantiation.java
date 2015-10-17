package com.commercetools.pspadapter.stripe.util;

import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.client.SphereClientFactory;

public class JavaClientInstantiation {
    public SphereClient instantiate() {
        final SphereClientFactory factory = SphereClientFactory.of();
        return factory.createClient(
                System.getenv("CTP_STRIPE_ADAPTER_CTP_PROJECT_KEY"),
                System.getenv("CTP_STRIPE_ADAPTER_CTP_CLIENT_ID"),
                System.getenv("CTP_STRIPE_ADAPTER_CTP_CLIENT_SECRET"));
    }
}