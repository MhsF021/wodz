# Erro no Checkout Pro com saldo e Mastercard de teste

## Texto para o suporte do Mercado Pago

Em uma integração brasileira de Checkout Pro via Preferences API e marketplace
(OAuth do vendedor), a preferência é criada e abre o checkout normalmente. Depois
de entrar com um comprador de teste diferente do vendedor e clicar em Pagar com
saldo fictício suficiente, o checkout mostra “Ops, ocorreu um erro.”. O teste
comparativo informado pelo usuário com Mastercard de teste também falhou.

A resposta HTTP 200 da requisição do fluxo contém `currentStep=error`,
`error_type=service_error`, `cause=unexpected_error` e `recoverable=true`.
A checagem `combination_is_eligible` informa `is_logged=true`,
`is_operation_valid=true` e `is_eligible=true`. A busca de pagamentos por
`external_reference` não encontrou uma tentativa financeira nas consultas
realizadas antes de receber esta resposta do navegador e após o teste com cartão.

Podem localizar a causa interna pelo código completo e pelo router_request_id
abaixo e verificar o motivo de a combinação aparecer indisponível no teste com
cartão, apesar de a checagem de elegibilidade aparecer aprovada?

| Campo | Valor |
| --- | --- |
| Data da resposta, convertida para Brasília | 11/10/2026, 05:24:53 (UTC−03:00) |
| Preference ID | `3740570257-46cdac40-a732-4b6f-b626-8b41c80464e2` |
| Client ID | `6035581613565283` |
| Collector ID | `3740570257` |
| Payer ID | `3744243828` |
| External reference | `31226105-7dac-4cdc-8e60-a5bfb2bf5006` |
| Checkout flow ID | `d7705b30-77d2-4700-8905-13456b4a3ac7` |
| Router request ID desta resposta | `97363fa3-4967-4a3f-bac3-31d4274f8116` |
| Código completo | `FL-ffed269d-29fd-4171-82c6-1c03a16f784d` |
| Método | `account_money` |
| Fluxo | `express_account_money` |
| Valor | R$ 210,00 |
| Moeda | `BRL` |
| Operação | `regular_payment` |
| Marketplace fee | 0 |
| Processing mode | `aggregator` |
| Serviço identificado pelo checkout | `N/A` |
| Service error detail | 0 |

## Tentativa com Mastercard

| Campo | Valor |
| --- | --- |
| Data da resposta, convertida para Brasília | 11/10/2026, 05:36:39 (UTC−03:00) |
| Preference ID | `3740570257-46cdac40-a732-4b6f-b626-8b41c80464e2` |
| Checkout flow ID | `bd48e587-b753-4891-a1f1-9b8f2c726d49` |
| Router request ID desta resposta | `3e8a9a4a-266d-439d-8072-26152c6be84f` |
| Código completo | `FL-ae622058-7d6b-4d00-b218-957bf336119c` |
| Método | `master` |
| Fluxo informado pelo checkout | `express_account_money` |
| combination_is_eligible.is_eligible | `true` |
| combination_is_available.has_combination | `false` |
| combination_is_available.is_available | `false` |
| Causa | `unexpected_error` |
| Detalhe | `service_error` |
| Serviço | `N/A` |
| Service error detail | 0 |
| Recuperável | `true` |

A resposta reconhece o método `master`, mas mantém a classificação de fluxo
`express_account_money`. Não foi encontrado um contrato público para interpretar
essa combinação de campos como uma causa específica.

## Verificações e limites do diagnóstico

- A preferência correspondia à aplicação configurada, tinha o valor esperado e
  ainda estava válida nas consultas anteriores. O erro do navegador aconteceu
  antes de sua validade terminar às 05:37:24 em Brasília. A tentativa com cartão
  também ocorreu antes desse limite, às 05:36:39; expiração não explica esse erro.
- O vendedor está ativo, no site MLB, com a tag `test_user`.
- A consulta pública do comprador confirma site MLB e status ativo, mas não
  disponibiliza a tag `test_user`; o usuário informou que usou uma conta de teste.
- A aplicação está ativa, sem bloqueio ou desativação nas consultas anteriores.
  O usuário confirmou no painel o produto Checkout Pro e o modelo Marketplace.
- A própria conta integradora é real, enquanto o vendedor é de teste. É um ponto
  para conferir com o provedor, sem evidência suficiente para atribuir a falha a
  essa combinação.
- `service_error` e `unexpected_error` localizam a etapa da falha, mas não revelam
  sua causa. A elegibilidade informada pelo checkout não garante que todas as
  validações internas do pagamento tenham passado.
- `liveMode=true` e `productive=true` no modelo do frontend não são evidência
  suficiente para classificar a conta do comprador como real.
- O usuário realizou o teste com cartão após receber as instruções do cenário
  `APRO` e informou outra falha. A resposta não registra os dados do titular para
  conferência; o método efetivamente informado é `master`.
- Tokens, cookies, identificadores de sessão do navegador e credenciais foram
  omitidos deste relatório. Nenhuma mensagem foi enviada ao suporte.

## Próximo passo

Solicitar ao suporte do Mercado Pago a consulta dos dois códigos completos e
router_request_ids. O retorno público não expõe o serviço ou a validação interna
responsável pela falha. Não há evidência suficiente para prescrever uma mudança
de token, URL de checkout ou forma de pagamento como correção.

Se o provedor pedir nova reprodução, iniciar uma preferência nova pelo WODZ,
pois esta preferência já venceu. Registrar apenas os códigos de erro e os
identificadores de correlação, evitando credenciais ou identificadores de sessão.

Referências oficiais:

- [Contas de teste e perfil integrador](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/test-accounts).
- [Compras de teste e cenário APRO](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/integration-test/test-purchases).
