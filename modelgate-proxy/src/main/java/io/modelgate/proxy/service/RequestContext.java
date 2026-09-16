package io.modelgate.proxy.service;

import java.util.UUID;

/**
 * Everything about a request that is not in the request itself: who sent it, which routing arm
 * served it. Built once per request by the controller and threaded through the data plane, so
 * no layer has to re-derive identity and every usage receipt can be attributed.
 */
public record RequestContext(String requestId, String keyId, String tenantId, String arm) {

    public static RequestContext of(String keyId, String tenantId, String arm) {
        return new RequestContext(UUID.randomUUID().toString(), keyId, tenantId, arm);
    }
}
