# Checkout WODZ no Mercado Pago

Atualizado em 13/10/2026. O pagamento de inscrições usa o Checkout Pro hospedado
no Mercado Pago, com redirecionamento na mesma aba.

## Fluxo

1. Abra o pagamento de uma inscrição pendente e confira a equipe, categoria e total.
2. Leia os termos de compra e a política de privacidade e marque o aceite.
3. **Pagar no Mercado Pago** abre o checkout hospedado na mesma aba.
4. No retorno, o backend consulta o pagamento no provedor. Parâmetros da URL
   não confirmam uma inscrição.
5. O backend verifica valor, moeda, referência, recebedor e modo financeiro
   antes de registrar a aprovação. A trigger confirma a equipe.

Os dados de cartão são preenchidos no Mercado Pago. A WODZ não coleta CPF
do pagador, não solicita QR Code à Payments API e não oferece Copia e Cola.
A preferência exclui o método `pix`, inclusive ao retomar preferências antigas.

## Contrato público

`POST /api/public/registrations/{código}/checkout`

```json
{"method":"CARD","termsAccepted":true}
```

O único método aceito é `CARD`. A resposta contém `id`, `url`, `expiresAt`
e `method`, sem campos de QR Code. A configuração pública não expõe flags
de recebimento centralizado.

- `GET /api/public/registrations/{código}`: resumo da inscrição.
- `GET .../checkout/{id}/details`: detalhes de um checkout hospedado.
- `GET .../checkout/{id}?paymentId=...`: consulta autenticada ao provedor.
- `GET .../payments/{id}`: situação de um pagamento, inclusive histórico.
- `POST /api/webhooks/mercadopago`: notificação assinada pelo segredo do checkout.

## Configuração

- `PAYMENT_PROVIDER=mercadopago`, `MP_CLIENT_ID` e `MP_CLIENT_SECRET`.
- Conta do organizador conectada por OAuth com PKCE.
- `MP_WEBHOOK_SECRET` e `MP_NOTIFICATION_URL` HTTPS.
- `FRONTEND_URL` HTTPS para retorno do checkout e OAuth.
- `APP_ENCRYPTION_KEY` para os tokens OAuth e `MP_PLATFORM_FEE_PERCENT` para
  a comissão da preferência hospedada.
- `MP_TEST_MODE=true` exige contas oficiais de teste. Produção exige conta real,
  `MP_TEST_MODE=false`, `SEED_DEMO=false` e segredos próprios.

As configurações de emissão centralizada, seu token separado, sua assinatura de
webhook e sua lista de responsáveis financeiros foram removidas.

## Idempotência e histórico

O servidor grava a tentativa antes de criar a preferência. Ao repetir a ação,
consulta pagamentos e valida a preferência no provedor antes de retomá-la.
Uma aprovação já encontrada impede uma nova cobrança.

Cobranças de métodos anteriores não podem ser emitidas ou retomadas pelo WODZ.
Enquanto uma cobrança antiga estiver válida, o servidor bloqueia outro checkout
para evitar duplicação. Após seu vencimento, consulta o provedor antes de criar
um checkout hospedado. O histórico de pagamentos e repasses e as migrações
aplicadas permanecem preservados; os relatórios em `docs/diagnostics` são
evidências históricas e não instruções de ativação da integração atual.

## Validação

Os testes locais verificam recusa de métodos removidos, ausência de QR Code no
contrato, aceite, idempotência, valores, retorno, assinatura e conciliação
histórica. Homologação de cartão e OAuth no ambiente publicado continua usando
as contas e o checkout do Mercado Pago.
