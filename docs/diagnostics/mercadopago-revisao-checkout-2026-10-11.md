# Revisão do checkout e das credenciais — 11/10/2026

Os campos básicos documentados para os dois fluxos estão presentes. Encontramos
lacunas no WODZ e as corrigimos, mas ainda não comprovamos a resolução da recusa
no Mercado Pago. Uma compra manual com cartão/saldo não valida a emissão de Pix.

## Configuração efetiva observada

| Propriedade | Valor verificado |
| --- | --- |
| Ambiente WODZ | `dev` |
| Provedor | `mercadopago` |
| `MP_TEST_MODE` | `true`: exige vendedor `test_user` |
| Aplicação configurada / OAuth | `6035581613565283` |
| Vendedor OAuth | `3740570257`, `test_account=true`, `live_mode=true` |
| Tipo de credencial OAuth | `APP_USR`, renovação com `test_token=false` |
| Aplicação da credencial direta de diagnóstico | `5387756444859415` |
| Comissão da plataforma | `0`: não enviada, valor da inscrição mantido |
| URLs de retorno/notificação | Públicas HTTPS via ngrok |

O backend não recebe nem usa `MP_ACCESS_TOKEN`: cobra com o OAuth cifrado do
organizador. A credencial direta é usada apenas em controles identificados.
O prefixo `APP_USR` é documentado para contas fictícias do Checkout Pro; não
indica por si só que uma conta de produção foi misturada ao teste.
`test_user` é a classificação da conta, enquanto `live_mode` é o modo financeiro
retornado pelo provedor. O sistema preserva e verifica ambos separadamente.

## Dados enviados

Cartão usa `/checkout/preferences`: item, preço BRL do servidor, referência,
retornos HTTPS, expiração, `auto_return` e exclusão de Pix. Confere vendedor,
aplicação, preço, referência e URL devolvida. Comissão zero não é acrescentada.
Não encontramos um campo público obrigatório de Marketplace ausente.

Pix usa `/v1/payments`: `transaction_amount`, `payment_method_id=pix`, descrição,
referência, expiração, `payer.email` e `payer.identification` do tipo CPF com
11 dígitos. Envia `notification_url` quando configurada e `application_fee`
quando há comissão. O cabeçalho contém OAuth do vendedor e chave de idempotência.
O e-mail vem do cadastro do responsável da equipe: ele não é automaticamente
substituído pelo e-mail de um comprador fictício do Mercado Pago.

Esse contrato cobre os campos básicos exigidos. Não garante que a combinação
de aplicação, grant e contas tenha autorização para operar cada produto.

## Controles que isolam a falha

| Operação | Credencial/aplicação | Resultado observado |
| --- | --- | --- |
| Checkout Pro mínimo, saldo fictício | Direta APP_USR / 538 | Pagamento `182637234147` aprovado |
| Checkout Pro mínimo, mesmas contas e valor, fora do WODZ | OAuth APP_USR / 603 | Erro do checkout; zero pagamentos |
| Pix isolado R$ 1,00 às 21:10 | Direta APP_USR / 538 | HTTP 401: `Unauthorized use of live credentials` |
| Pix isolado, mesmos campos e vendedor | OAuth APP_USR / 603 | HTTP 500: `user_allowed_only_in_test` |
| Pix isolado com OAuth `test_token=true`, teste anterior autorizado | TEST / 603 | Mesmo erro; sem ID/QR |

O controle Pix de 21:10 confirmou as duas identidades antes dos POSTs, usou
CPF e e-mail fictícios, validade de 31 minutos e referências próprias. Não
pagou, renovou OAuth ou alterou inscrições. O OAuth APP_USR restaurado no teste
anterior foi mantido. O [JSON comparativo](mercadopago-pix-credential-comparison-2026-10-11.json)
registra somente campos filtrados. Os [controles anteriores](mercadopago-testes-controlados-2026-10-11.md)
documentam a aprovação com saldo e a reprodução com OAuth.

A variável aplicação/modalidade de credencial não foi isolada pela comparação
direta versus OAuth. Portanto, não atribuímos a causa exclusivamente ao código
WODZ nem a uma falha interna do provedor. A aplicação e autorização usadas pelo
WODZ continuam fazendo parte da investigação.

## Lacunas corrigidas no WODZ

- **Origem da autorização:** persiste `application_id` na V26. Uma cobrança nova
  não aceita OAuth conhecido de outra aplicação; legados precisam renovar e
  validar antes de registrar a origem. Pagamentos antigos preservam suas
  credenciais quando há reconexão em outra aplicação.
- **Timeout Pix:** recupera um pagamento pendente com a mesma referência antes
  de tentar emitir. Valida ID, dados financeiros e prazo antes de associar o QR.
- **Prazo da retentativa:** preserva a chave/payload original e bloqueia POSTs
  quando restam menos de 30 minutos mais margem. Não cria outra cobrança
  pagável enquanto a tentativa anterior ainda é válida e incerta.
- **Retorno Pix:** não exige a URL HTTPS de retorno do Checkout Pro, porque Pix
  é exibido na plataforma e não envia `back_urls`.
- **Diagnóstico:** logs de correlação registram nomes/presença/formato dos
  campos, aplicação e tipo/origem da credencial. Não imprimem CPF, e-mail,
  tokens, cookies, QR Code ou corpos brutos. O erro sobre ambiente não culpa
  automaticamente a conta do organizador.

Essas correções tratam falhas reais de validação e recuperação. Não demonstram
que foram a causa da tela genérica anterior, que ocorreu antes de existir um
pagamento mesmo com preferência mínima fora do WODZ.

## Validação e resultado após atualização local

Em uma execução completa, passaram **258 testes em 35 suítes**, sem falhas,
erros ou testes ignorados. A última revisão do callback acrescentou uma
regressão de origem desconhecida; seus **17 testes específicos** também
passaram. Isso soma 259 casos distintos nos relatórios, não uma segunda
execução completa de 259 testes. O build Docker do backend passou; Flyway validou 26
migrações e aplicou V26 sobre o banco existente. A API pública respondeu com
`mercadoPagoTestMode=true` e as inscrições foram preservadas.

Os vínculos ativos utilizados após a atualização confirmaram
`application_id=6035581613565283`, vendedor fictício `3740570257`,
`test_account=true`, `live_mode=true` e access/refresh tokens cifrados.
Não foi constatado uso de token direto da app 538 pelo backend.

Uma nova tentativa feita pelo usuário às 21:35 de Brasília foi observada nos
logs do código atualizado: checkout 5, método Pix, R$ 100,00. A requisição
usou OAuth APP_USR da app 603, vendedor classificado como fictício e modo de
contas de teste ativo. Valor, descrição, CPF de 11 dígitos, e-mail com formato
válido, notificação e vencimento de 31 minutos estavam presentes. A resposta
foi HTTP 500, `user_allowed_only_in_test`, request ID
`8daa5add-d859-4dfa-b55b-7e3aa8dda1cf`, sem ID de pagamento retornado.
Referência: `24809221-b94b-4d17-b6b3-ddc03543e8e0`.

Isso demonstra que as proteções e os logs estão em uso e que a recusa
persiste. O formato válido de e-mail/CPF não comprova associação ao comprador
fictício esperado pelo provedor. Não se infere sucesso nem confirmação de
inscrição a partir do envio, da preferência ou de `live_mode=true`.

## Próxima comparação necessária

Reproduzir o caso que aprovou com **a mesma aplicação e o mesmo tipo de
autorização usados pelo WODZ**, registrando `client_id`, `collector_id`, URLs
retornadas e resultado. O teste direto já aprovado não substitui o OAuth de
cada organizador. Ainda falta confirmar a configuração/grant de teste da app
603 para Checkout Pro Marketplace e a combinação suportada para Pix.

Não foram inventados scopes, marcações internas de Marketplace ou headers de
parceiros, nem trocada a credencial global para contornar as verificações.

## Referências oficiais consultadas

- [Contas de teste e prefixo APP_USR no Checkout Pro](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/test-accounts).
- [Pix: dados, QR e prazo de 30 minutos a 30 dias](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/integration-configuration/integrate-pix?scope=prod).
- [OAuth e test_token](https://www.mercadopago.com.br/developers/pt/docs/security/oauth/creation).
- [Marketplace Split 1:1](https://www.mercadopago.com.br/developers/pt/docs/split-payments/split-1-1/integration-configuration/integrate-marketplace).
