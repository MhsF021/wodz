# Análise comparativa dos HARs do Checkout Pro — 11/10/2026

Atualização posterior: os [testes controlados com login e compra executados](mercadopago-testes-controlados-2026-10-11.md)
aprovaram uma compra com o token direto do vendedor e reproduziram o erro com
o OAuth do marketplace mesmo em uma preferência mínima. A comparação delimita
melhor a investigação do que as capturas isoladas abaixo.

A captura identifica uma etapa que não aparecia nas respostas isoladas: após
clicar em Pagar, o Checkout Pro solicita reautenticação do comprador e termina
em erro ao retomar esse fluxo. Isso concentra a investigação na transição de
reautenticação, mas não revela a causa interna nem comprova um defeito específico.

O teste de comparação com login prévio, informado pelo usuário e registrado
na segunda captura, reproduziu a mesma sequência. Autenticar antes de abrir
o checkout não resolveu o caso; o momento do login não explica, sozinho, o erro.

## Sequência observada

Horários convertidos do UTC do HAR para Brasília (UTC−03:00). A coluna de horário
representa o início da requisição, não o momento exato de recebimento da resposta.

| Horário | Requisição do Mercado Pago | HTTP | Resultado no corpo da resposta |
| --- | --- | --- | --- |
| 13:46:24.210 | `POST /checkout/v1/payment/api/flow` | 200 | `preloadedServerData.currentStep=review` |
| 13:47:08.754 | `PUT /checkout/v1/payment/api/flow/78ea75f1-49d6-44cf-a34a-0a51685f3e22` | 200 | `currentStep=challenge`; componente `group_challenge`, `challenge=reauth` |
| 13:47:10.025 | `GET /checkout/v1/payment/api/reauth` | 200 | `{}`; sem campo `url` |
| 13:47:10.504 | `PUT /checkout/v1/payment/api/flow/78ea75f1-49d6-44cf-a34a-0a51685f3e22` | 200 | `currentStep=error`; `service_error`, `unexpected_error` |

O primeiro PUT foi disparado pela ação `review_express_pay`, com
`prev_step=review` e método `account_money`. O segundo PUT contém
`prev_step=challenge`, sem `values` e sem parâmetros de conclusão de
reautenticação nos `urlParams`. A captura não mostra uma página para concluir
o segundo fator entre essas duas requisições.

O tracking do desafio é `/checkout_off/review/challenge_second_factor_auth`.
A consulta de reautenticação levou aproximadamente 474 ms e retornou
`X-Cache: Miss from cloudfront`, sem falha HTTP.

## Identificadores para consulta do suporte

| Campo | Valor |
| --- | --- |
| Preference ID | `3740570257-bac366d8-04db-46b5-aa25-daeb1f28c05f` |
| External reference | `3315cb83-6cf2-4ee2-b177-685fff968ef9` |
| Client ID | `6035581613565283` |
| Collector ID | `3740570257` |
| Payer ID | `3744243828` |
| Checkout flow ID | `78ea75f1-49d6-44cf-a34a-0a51685f3e22` |
| Router request ID | `801bd68e-23e5-42c4-bf5f-7bfcb161bb16` |
| Código completo do erro | `FL-5a5f4416-894a-43a5-b54f-620d635447f7` |
| X-Request-ID do POST inicial | `855a6fc2-864f-4f47-9423-b5ef5e0af4d6` |
| X-Request-ID do PUT que exige reautenticação | `1941b6d9-048c-4401-b2e0-ff3d56ae9734` |
| X-Request-ID do GET de reautenticação | `90a01b25-68ce-40e9-88fe-78a6e5bd746a` |
| X-Request-ID do PUT que retorna erro | `ba0df045-63d5-4935-9d8b-8439fc165018` |
| Valor e moeda | R$ 210,00 / `BRL` |
| Método desta captura | `account_money` |
| Serviço identificado no erro | `N/A` |
| Service error detail | 0 |
| Recuperável | `true` |

## Segunda captura: comprador autenticado antes do checkout

O usuário informou que seguiu o teste de isolamento solicitado: nova sessão
anônima, login do comprador antes de abrir o checkout e nova tentativa com saldo.
O HAR não contém as chamadas anteriores de login; o checkout começa na revisão
com `is_logged=true`, e o comprador identificado é o mesmo da primeira captura.

| Início da requisição em Brasília | Requisição | HTTP | Resultado |
| --- | --- | --- | --- |
| 14:08:00.842 | `POST /checkout/v1/payment/api/flow` | 200 | `review` |
| 14:08:08.454 | `PUT /checkout/v1/payment/api/flow/13d11552-2a0a-4ce6-9f83-281bb0e48379` | 200 | `challenge`, `challenge=reauth` |
| 14:08:09.727 | `GET /checkout/v1/payment/api/reauth` | 200 | `{}`, sem `url` |
| 14:08:10.201 | `PUT /checkout/v1/payment/api/flow/13d11552-2a0a-4ce6-9f83-281bb0e48379` | 200 | `error`, `unexpected_error`, `service_error` |

O último PUT informa `prev_step=challenge`, sem `values`; os `urlParams`
contêm a preferência, o router request ID e o parâmetro de dispositivo, sem
parâmetros de conclusão do desafio. O `stepDate` da resposta de erro corresponde
a 14:08:14 em Brasília, também antes do vencimento às 14:08:49.444.

| Identificador da segunda captura | Valor |
| --- | --- |
| Preference ID | `3740570257-bac366d8-04db-46b5-aa25-daeb1f28c05f` |
| Checkout flow ID | `13d11552-2a0a-4ce6-9f83-281bb0e48379` |
| Router request ID | `8a5c3b24-7706-4ce7-b9d6-3b09832a4c0d` |
| Código completo do erro | `FL-bc477356-f278-4ef6-aa34-5aa7ef74e373` |
| X-Request-ID do POST inicial | `07b61040-9532-439f-9e15-8f7e656f7035` |
| X-Request-ID do PUT que exige reautenticação | `4153bb1d-a53b-4097-a265-f58d1d0bf554` |
| X-Request-ID do GET de reautenticação | `04b764b2-8e6e-49f1-93ca-80e1b35df760` |
| X-Request-ID do PUT que retorna erro | `5c1db416-bfcf-4bd4-925c-a82e0f12a7d1` |

São 86 requisições, de 14:07:59.631 a 14:08:11.712, sem HTTP 4xx/5xx, status 0
ou campo de erro de rede preenchido: 80 respostas 200, quatro 204, uma 302 e
uma 304. O arquivo enviado manteve o mesmo nome do anterior; os achados da
primeira captura foram preservados neste relatório.

## Verificação pela API oficial

Consulta realizada em 11/10/2026 às 13:53:20 em Brasília, somente para leitura:

- Preferência criada às 13:38:53.780 e válida até 14:08:49.444. Ainda estava
  válida na tentativa capturada e no momento da consulta.
- Aplicação da preferência corresponde à aplicação configurada no WODZ.
- Um item de R$ 210,00 em BRL; `marketplace_fee=0`.
- Vendedor ativo, brasileiro (`MLB`), identificado como `test_user`.
- Busca por `external_reference`: **zero pagamentos registrados**.

Nova consulta após a segunda captura, às 14:10:40 em Brasília, confirmou a
mesma preferência e **zero pagamentos registrados**. Naquele momento ela já
estava vencida. Isso não explica o erro capturado, ocorrido antes do vencimento.
As duas capturas usam fluxos diferentes do Mercado Pago para a mesma preferência.
O reaproveitamento é compatível com o comportamento do WODZ de retomar uma
preferência ainda válida. Os HARs não contêm a chamada do WODZ que abriu o checkout.

## Outras observações e limites

- A primeira captura tem 87 requisições, de 13:46:22.939 a 13:47:12.110. Nenhuma retornou
  HTTP 4xx/5xx ou status 0: foram 81 respostas 200, quatro 204, uma 302 e uma 304.
  Não há campo de erro de rede preenchido nas entradas analisadas.
- A captura começa no redirecionamento do Mercado Pago. Não contém as chamadas
  do WODZ ou ngrok, nem permite inspecionar o payload original enviado pelo
  backend à Preferences API ou o histórico anterior de login do comprador.
- O usuário confirmou que o teste foi feito em janela anônima, com login do
  comprador realizado dentro do checkout. Essa informação foi fornecida após
  a análise do HAR. O login estava aceito na revisão (`is_logged=true`); o erro
  surgiu depois do pedido adicional de reautenticação. Não há evidência de que
  realizar o login dentro do checkout seja, por si só, a causa da falha.
- Esta captura usa **saldo**, não cartão. O teste anterior com Mastercard está
  documentado no [relatório das tentativas anteriores](mercadopago-checkout-2026-10-11.md).
- O tracking informa `is_logged=true`, `is_operation_valid=true` e
  `is_eligible=true`; isso não garante aprovação das validações posteriores.
- No tracking inicial, `available_methods.account_money` aparece como
  `unavailable`, enquanto `account_money_black` aparece recomendado na revisão
  e é o método selecionado no primeiro PUT. Essa diferença de classificação
  merece análise pelo provedor, mas não comprova saldo insuficiente.
- Os JavaScripts do próprio Mercado Pago incluídos no HAR tratam uma resposta
  de reautenticação sem `url` como `reauthUrl=null`. O cliente possui caminhos
  para continuar o fluxo sem essa URL. Portanto, `{}` isoladamente não prova
  uma resposta inválida: a evidência relevante é a sequência completa até o erro.
- Não há uma recusa financeira com `status_detail` que permita atribuir o erro
  a saldo, cartão, expiração, comissão ou credenciais.
- Cookies, tokens, dados pessoais e identificadores de sessão foram omitidos.
  O HAR original não foi alterado ou copiado para o repositório.

## Pergunta específica para o suporte do Mercado Pago

> Em duas tentativas em janela anônima com o mesmo comprador de teste, o checkout
> retorna o desafio `challenge_second_factor_auth` / `reauth`; a chamada de
> reautenticação responde HTTP 200 com `{}` e a retomada imediata falha com
> `service_error`. Na primeira, o login foi realizado dentro do checkout; na
> segunda, antes de abrir o checkout, e o resultado não mudou. O novo código é
> `FL-bc477356-f278-4ef6-aa34-5aa7ef74e373`. Podem confirmar se `{}` é o retorno
> esperado para essa conta de teste e localizar pelos request IDs qual validação
> ou serviço produz o erro na retomada? A preferência estava válida nas duas
> tentativas, e a busca de pagamentos pela referência externa não encontrou
> uma transação.

## Resultado do teste de isolamento

Foi comparado o login dentro do checkout com o login prévio na mesma sessão
anônima usada para abrir o WODZ. A sequência e a classe do erro permaneceram
iguais. Esse teste enfraquece a hipótese de que o momento do login explique a
falha; não exclui outros aspectos da sessão ou da configuração da conta.

A informação pública continua insuficiente para prescrever uma alteração no
payload, nas credenciais ou no código do WODZ como correção. O próximo passo
útil é a consulta pelo provedor dos request IDs da reautenticação e do PUT que
retorna erro. Repetir o mesmo teste de login não acrescenta informação.

Referências oficiais:

- [Compras de teste](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/integration-test/test-purchases).
- [Contas de teste e validação de login](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/test-accounts).
