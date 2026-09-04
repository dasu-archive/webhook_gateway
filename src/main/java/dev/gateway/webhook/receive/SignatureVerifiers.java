package dev.gateway.webhook.receive;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** endpoint.provider 값으로 검증기를 찾는다. 제공자 추가는 SignatureVerifier 구현 하나로 끝난다. */
@Component
public class SignatureVerifiers {

    private final Map<String, SignatureVerifier> byProvider;

    public SignatureVerifiers(List<SignatureVerifier> verifiers) {
        var map = new HashMap<String, SignatureVerifier>();
        for (SignatureVerifier verifier : verifiers) {
            var key = normalize(verifier.provider());
            var previous = map.put(key, verifier);
            if (previous != null) {
                throw new IllegalStateException("provider '" + key + "' 검증기가 둘 이상이다: "
                        + previous.getClass().getName() + ", " + verifier.getClass().getName());
            }
        }
        this.byProvider = Map.copyOf(map);
    }

    public Optional<SignatureVerifier> find(String provider) {
        return provider == null ? Optional.empty() : Optional.ofNullable(byProvider.get(normalize(provider)));
    }

    public Set<String> supported() {
        return new TreeSet<>(byProvider.keySet());
    }

    private static String normalize(String provider) {
        return provider.trim().toLowerCase(Locale.ROOT);
    }
}
