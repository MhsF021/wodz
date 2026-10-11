package br.com.wodz.dto;

import java.time.Instant;

public final class PayoutDtos {
    private PayoutDtos() { }

    public record PayoutView(boolean configured, boolean paymentUnavailable, boolean connected,
                             String accountLabel, String providerUserId, boolean liveMode,
                             Instant connectedAt, boolean testMode, Instant updatedAt) { }

    public record AuthorizationView(String authorizationUrl) { }
}
