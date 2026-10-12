package br.com.wodz.payment;

import java.time.Instant;

public record GatewayPix(GatewayPayment payment, String qrCode, String qrCodeBase64, Instant expiresAt) { }
