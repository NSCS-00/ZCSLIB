package zcslib.service;

import zcslib.api.TrustLevel;

/**
 * Service entry metadata returned by {@code service:get:meta}.
 *
 * @param api         the registered interface
 * @param impl        the implementation instance
 * @param providerId  plugin that registered this service
 * @param trust       provider's trust level
 * @param registeredAt epoch millis when registered
 */
public record ServiceEntry(
        Class<Object> api,
        Object impl,
        String providerId,
        TrustLevel trust,
        long registeredAt) {}
