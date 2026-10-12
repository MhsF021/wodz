package br.com.wodz.payment;

/** Os dados do pagador nunca devem aparecer em logs. */
public record PixCommand(CheckoutCommand checkout, String payerEmail, String payerCpf, String notificationUrl) { }
