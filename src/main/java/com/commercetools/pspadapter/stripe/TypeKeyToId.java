package com.commercetools.pspadapter.stripe;

import io.sphere.sdk.client.SphereClient;
import io.sphere.sdk.queries.PagedQueryResult;
import io.sphere.sdk.types.Type;
import io.sphere.sdk.types.queries.TypeQuery;
import io.sphere.sdk.types.queries.TypeQueryModel;

import java.util.HashMap;
import java.util.Optional;

public class TypeKeyToId {
    final private SphereClient client;
    final private static HashMap<String, String> keyToIdCache = new HashMap<>();

    public TypeKeyToId(SphereClient client) {
        this.client = client;
    }

    public Optional<String> getId(String key) {
        final Optional<String> cachedId = Optional.ofNullable(keyToIdCache.get(key));
        if (!cachedId.isPresent()) {
            final Optional<String> typeIdFromCTP = getTypeIdFromCTP(key);
            typeIdFromCTP.ifPresent(id -> keyToIdCache.put(key, id));
            return typeIdFromCTP;
        } else return cachedId;
    }

    private Optional<String> getTypeIdFromCTP(String key) {
        try {
            final PagedQueryResult<Type> typesResult = client
                    .execute(TypeQuery.of().withPredicates(TypeQueryModel.of().key().is(key)))
                    .toCompletableFuture().get();
            return typesResult.head().map(t -> t.getId());
        } catch (Exception e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }
}
