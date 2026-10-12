# Teste autorizado de Pix e OAuth sandbox — 11/10/2026

O parâmetro `test_token=true` foi testado após autorização explícita do usuário.
O Mercado Pago gerou uma credencial TEST para o vendedor esperado, mas a Payments
API continuou recusando a criação de Pix com HTTP 500 e motivo
`user_allowed_only_in_test`. Não foi devolvido ID de pagamento nem QR Code.

Aplicação WODZ: `6035581613565283`. Vendedor: `3740570257`, brasileiro, ativo e
com tag `test_user`, confirmado pela API oficial antes das operações. Todas as
requisições tiveram referência e chave de idempotência próprias, valor R$ 1,00,
CPF fictício do exemplo oficial, validade de 31 minutos e `payment_method_id=pix`.
Não houve envio de URLs, comissão, dados de cartão ou solicitação para pagar.

## Comparação

| Controle | Credencial | Pagador fictício | Resultado |
| --- | --- | --- | --- |
| Inicial | OAuth APP_USR, `test_token=false` | E-mail no domínio example.com | HTTP 500, `user_allowed_only_in_test`, sem QR |
| Sandbox autorizado, 20:17 | OAuth TEST, `test_token=true` | Mesmo domínio example.com | Mesmo erro, sem QR |
| Padrão de pagador, 20:22 | OAuth APP_USR restaurado | `test_payer_[digits]@testuser.com` | Mesmo erro, sem QR |
| Sandbox + padrão de pagador, 20:24 | OAuth TEST | `test_payer_[digits]@testuser.com` | Mesmo erro, sem QR |

Referências:

- Inicial: `pixdiag-e600dffc-7e74-4ca8-9e07-8f2be1e91107`.
- Sandbox: `pixdiag-f786096b-4f60-4c6b-9371-9d62c7f47326`.
- Padrão de pagador APP_USR: `pixdiag-03279448-31ce-48ca-8a9c-8f5428e0ef2e`.
- Sandbox + padrão: `pixdiag-ea93f7a0-deb1-49a5-9ef0-6f5d1864c4df`.

O domínio de pagador foi usado como controle de diagnóstico. O padrão
`test_payer_[digits]@testuser.com` aparece na API de clientes; isso não demonstra
que corresponde à conta de comprador fictício original nem constitui uma
homologação de Pix no marketplace. A documentação de migração descreve o uso de
um padrão de usuário de teste para Pix/boleto na Payments API.

## Recuperação da autorização de cartão

O OAuth de sandbox retornou `access_token` TEST, `refresh_token` e `user_id`
correto, mas não incluiu `live_mode`. A emissão da credencial nova invalidou o
access token APP_USR anterior: `/users/me` com o acesso antigo passou a HTTP 401.
O refresh token rotacionado foi salvo cifrado antes de prosseguir.

Para recuperar o vínculo do Checkout Pro, o refresh foi renovado com
`test_token=false`. Retornou APP_USR, `live_mode=true`, o mesmo vendedor e nova
validade. Access e refresh tokens foram armazenados cifrados. `/users/me` voltou
a HTTP 200; a preferência anterior também foi consultada com HTTP 200 e confirmou
o collector `3740570257` e a aplicação `6035581613565283`.

A segunda comparação TEST incluiu restauração do APP_USR ao terminar, mesmo se
a tentativa Pix falhasse. O resultado registrou `restoration_complete=true`.
Não se deixou o vínculo conectado com um access token TEST ou com o acesso
antigo invalidado. Nenhuma inscrição WODZ foi modificada pelos testes.

A auditoria final, às 20:26 de Brasília, confirmou novamente a preferência
existente com o OAuth salvo no banco, o vendedor e a aplicação corretos. A busca
pelas três referências desta rodada retornou zero pagamentos. A inscrição usada
na verificação permaneceu `PENDING_PAYMENT`, valor R$ 250,00 e sem checkout.

Isso confirma a recuperação da autorização para consultar a API. Não comprova
a aprovação de um pagamento com cartão nem resolve o erro anterior do Checkout Pro.

## O que se pode concluir

- A renovação sandbox funciona e retornou a identidade de vendedor esperada.
- Trocar apenas `test_token=false` por `true` não resolveu a emissão Pix testada.
- O controle com outro formato de e-mail fictício também não produziu QR Code.
- As verificações do WODZ não provocaram essas recusas: os testes foram chamadas
  diretas à API oficial, independentes das inscrições.
- Não foi demonstrado se o impedimento está na aplicação, no uso de vendedor
  fictício na Payments API, no pagador simulado ou em outra regra do provedor.
  O código interno precisa ser interpretado pelo Mercado Pago.

## Texto para encaminhar ao suporte

> Na aplicação Marketplace `6035581613565283`, o vendedor fictício brasileiro
> `3740570257` está ativo e confirmado por `/users/me` com tag `test_user`.
> Testamos `POST /oauth/token`, grant `refresh_token`, com `test_token=true`.
> A API retornou HTTP 200, credencial TEST e o mesmo `user_id`.
> Depois enviamos `POST /v1/payments` com esse OAuth, `payment_method_id=pix`,
> valor R$ 1,00, CPF fictício do exemplo oficial, e-mail fictício, validade de
> 31 minutos, referência única e `X-Idempotency-Key`.
> A criação retornou HTTP 500, `internal_server_error`, com a mensagem
> `fill and validate error list: user_allowed_only_in_test : 400`, sem ID ou QR.
> Referência: `pixdiag-ea93f7a0-deb1-49a5-9ef0-6f5d1864c4df` (11/10/2026,
> aproximadamente 20:24 de Brasília). O mesmo erro ocorreu com APP_USR e com
> outros formatos de e-mail fictício. O APP_USR foi restaurado após o teste.
> Para Pix via Payments API em marketplace, essa conta fictícia pode atuar como
> collector? Qual credencial, formato de pagador e configuração são exigidos
> nessa combinação? Qual regra gerou `user_allowed_only_in_test` nessa requisição?

Nenhuma mensagem foi enviada ao suporte. Não inclua tokens, senhas ou cookies
ao encaminhar esses identificadores.

## Evidências salvas

- [OAuth sandbox e Pix](mercadopago-pix-sandbox-2026-10-11.json).
- [Primeira restauração do OAuth](mercadopago-pix-oauth-restored-2026-10-11.json).
- [Controle APP_USR com padrão de pagador](mercadopago-pix-payer-pattern-2026-10-11.json).
- [Controle TEST com padrão e restauração](mercadopago-pix-sandbox-payer-pattern-2026-10-11.json).
- [Auditoria final do vínculo e referências](mercadopago-pix-final-audit-2026-10-11.json).

Os arquivos contêm somente metadados e erros tratados, sem credenciais ou dados
pessoais de usuários. O código de erro não foi tratado como aprovação nem usado
para reduzir verificações financeiras.

Referências oficiais:

- [OAuth e test_token](https://www.mercadopago.com.br/developers/pt/docs/security/oauth/creation).
- [Renovação OAuth](https://www.mercadopago.com.br/developers/pt/docs/security/oauth/renewal).
- [Pix na Payments API](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/integration-configuration/integrate-pix?scope=prod).
- [API de clientes e padrão de e-mail de teste](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api-payments/customers/create-customer/post).
