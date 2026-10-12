# PIX: recusa de autorização com Checkout Pro funcionando — 12/10/2026

O Cartão / Outros voltou a aprovar pagamentos pela aplicação correta
`4961044287054715`. O PIX falha em uma operação diferente: criação direta de
pagamento em `POST /v1/payments`. A mensagem genérica do WODZ ocultava uma
resposta **HTTP 401** de autorização, não um timeout.

## Evidências atuais

| Campo | Valor confirmado |
| --- | --- |
| Aplicação configurada, OAuth e preferência | `4961044287054715` |
| Conta recebedora fictícia | `3758553874` |
| Tipo de credencial | `APP_USR`, sem renovação durante a investigação |
| Vínculo | Ativo, `test_account=true`, `live_mode=true` |
| Cartão / Outros aprovado | `182705002681`, R$ 250,00 com saldo, 01:01:48 BRT |
| Registro local | `APPROVED`, equipe 443 `CONFIRMED` |
| PIX da inscrição | Checkout 2, equipe 444, R$ 250,00, 01:01:57 BRT |
| Referência PIX | `4297cdc0-a7ba-4d1e-a021-e59e428cca82` |
| Resultado | HTTP 401; sem ID de pagamento ou QR |
| Request ID PIX | `c2e0287c-7359-4f49-83d8-e7c268d4d6c8` |

Os logs da requisição PIX confirmam valor, descrição, e-mail com formato válido,
CPF de 11 dígitos, vencimento de 31 minutos e notificação. A assinatura do
webhook é utilizada no recebimento das atualizações; não concede permissão de
criação à Payments API.

## Controle isolado executado

Sem alterar o OAuth ou as inscrições, foram consultados `/users/me` e
`/applications/4961044287054715`, ambos HTTP 200. A identidade foi confirmada
como conta brasileira de teste ativa. A aplicação está ativa, não bloqueada,
não desabilitada e com PKCE. Sua lista `scopes` retornou `read`, `offline_access`
e `write`. Essa lista é da aplicação e não comprova os escopos efetivos do grant
usado pelo vendedor.

Depois dessa validação, um PIX fictício isolado de R$ 1,00, com CPF/e-mail
fictícios e validade de 31 minutos, retornou:

```text
HTTP 401
error: unauthorized
message: Unauthorized use of live credentials
```

Não retornou ID de pagamento ou QR. Referência do controle:
`540552fb-094d-44a9-8698-1d4947e64535`; request ID:
`de72ba17-61b2-4188-a8e9-685a200d157a`. A busca financeira retornou zero
pagamentos tanto para essa referência quanto para a referência PIX da inscrição.
O pagamento de cartão permaneceu aprovado na API e no banco. Não houve pagamento
do controle, renovação OAuth, alteração de vínculo ou exclusão de registro.

Artefatos filtrados:

- [Controle PIX](mercadopago-pix-probe-2026-10-12.json).
- [Consulta do cartão e buscas PIX](mercadopago-pix-and-card-audit-2026-10-12.json).

## Interpretação da documentação

A [referência de criação de pagamentos](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api-payments/create-payment/post)
associa esse erro 401 à ausência da permissão/escopo necessário para criar
pagamentos. Isso prioriza a investigação de autorização da Payments API, mas
não confirma que o prefixo APP_USR seja errado ou que o ambiente foi misturado.

O WODZ fixava `scope=read write offline_access` na URL de autorização e na troca
do código. O [fluxo específico de Marketplace](https://www.mercadopago.com.br/developers/pt/docs/split-payments/split-1-1/integration-configuration/create-configuration)
omite esse parâmetro e exemplifica um grant com `offline_access payments write`.
Não está provado que a seleção explícita tenha retirado uma permissão: o padrão
do provedor precisa ser observado em uma autorização nova.

A [referência OAuth](https://www.mercadopago.com.br/developers/pt/reference/authentication/oauth/_oauth_token/post)
documenta `read`, `write` e `offline_access` como valores solicitáveis; portanto,
não foi inventado um parâmetro `payment` ou `payments`, nem adicionado header
de sandbox de outro produto.

## Alterações de código

- Removida a seleção explícita de `scope` da URL e da troca do código, deixando
  as permissões configuradas na aplicação seguirem o fluxo de Marketplace.
- Preservados PKCE, state, aplicação, vendedor, `test_token=false`, APP_USR e
  validação literal de `live_mode`. Nenhum token existente foi rotacionado.
- Log seguro de sucesso OAuth informa apenas grant, HTTP, tipo de credencial,
  modo financeiro e nomes de escopos conhecidos. Valores desconhecidos são
  contados, sem imprimir seu conteúdo. Não são registrados tokens, códigos,
  URLs, CPF, e-mail ou corpos brutos.
- `Unauthorized use of live credentials` em HTTP 401 recebe o motivo fixo
  `unauthorized_live_credentials` nos logs e uma mensagem sobre permissão de
  criação PIX, em vez de orientar aguardar alguns instantes.

As 119 regressões de Gateway, OAuth, vínculo e PaymentService passaram em
conjunto, sem falhas, erros ou casos ignorados. O teste de conexão foi ajustado
ao contrato novo sem `scope` explícito. Sintaxe do script de controle e
`git diff --check` passaram. Não houve mudança no frontend ou na estrutura do banco.

## Próximo teste necessário

O código está salvo; o contêiner em execução ainda usa o código anterior.
O usuário deve reconstruir apenas o backend, preservando o banco:

```bash
docker compose up -d --build --force-recreate backend
```

Em Recebimentos, usar **Renovar autorização** na mesma aplicação e mesma conta
que aprovaram o cartão. Essa interação gera um grant pelo fluxo novo e permite
observar os escopos efetivos no log; uma simples recarga não altera o OAuth já
concedido. Depois, testar PIX em outra inscrição de teste ou aguardar o checkout
anterior vencer, preservando a referência e o payload de uma tentativa pendente.

Se continuar HTTP 401, o controle não resolveu a permissão e será necessário
confirmar com o Mercado Pago a capacidade PIX/Payments API dessa aplicação e
desse grant de teste. O request ID e os escopos retornados permitirão uma pergunta
específica. Não se afirma que o PIX foi resolvido antes dessa autorização e teste.
