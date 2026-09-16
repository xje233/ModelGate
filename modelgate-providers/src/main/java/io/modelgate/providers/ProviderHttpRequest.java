package io.modelgate.providers;

import java.util.Map;

/** An immutable, ready-to-send upstream HTTP request (URL + headers + JSON body). */
public record ProviderHttpRequest(String url, Map<String, String> headers, String body) {

    public ProviderHttpRequest {
        headers = Map.copyOf(headers);
    }
}
