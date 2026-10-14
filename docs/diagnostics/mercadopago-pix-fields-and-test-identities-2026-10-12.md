# Pix: campos enviados, identidade de teste e autorização

Análise de 12/10/2026, com revisão de código, documentação oficial e controles
fictícios entre 12:36 e 12:40 de Brasília. O Checkout Pro aprovado usado como
referência é o pagamento `182705002681` feito pelo WODZ, preservado pelo usuário.
O antigo teste manual externo descartado pelo usuário não integra esta análise.

## Conclusões verificadas

1. O JSON Pix utiliza os nomes e a estrutura apresentados pela Payments API.
   Marketplace não exige uma tag adicional na cobrança. O token OAuth identifica
   o recebedor; `application_fee` é a comissão opcional dessa API.
2. A origem do e-mail merece ajuste no cenário de teste. O WODZ usa o cadastro
   do capitão, sem obter a identidade do comprador fictício do Mercado Pago.
   O último rascunho observado usa e-mail fora de `@testuser.com`.
3. Essa diferença não explica sozinha a recusa atual: a criação de Pix também
   retornou 401 com o e-mail de teste que o próprio Mercado Pago registrou no
   pagamento aprovado, junto ao CPF de exemplo da documentação.
4. Retirar o CPF ou adicionar nome e sobrenome manteve o mesmo 401. Retirar
   todo o objeto `payer` produziu 500, sem pagamento, em um controle propositalmente
   incompleto. Não é uma alternativa válida de payload.
5. O perfil de conta de teste Marketplace/Integrador e o modelo Marketplace
   da aplicação são conceitos diferentes. A leitura `test_user` confirma
   conta fictícia, mas não comprova todos os papéis e direitos da conta.

## Por que Cartão / Outros funciona sem CPF e e-mail no WODZ

O WODZ envia uma preferência ao endpoint `/checkout/preferences`, sem `payer`.
O Mercado Pago trata a identificação do comprador dentro do seu checkout.
O pagamento aprovado contém um comprador e um e-mail fictício associado a ele,
mesmo que esses dados não tenham sido enviados pelo formulário WODZ.

Já o Pix é criado diretamente em `/v1/payments`. Seu formulário WODZ não passa
pela sessão do comprador no Mercado Pago: envia um `payer` construído pelo
backend. O guia oficial pede dados do comprador para esse fluxo. Uma caixa
postal real não é necessária para usar os dados fictícios de teste; a identidade
precisa seguir o cenário suportado pela API, sem pressupor que qualquer e-mail
fictício será reconhecido como comprador de teste.

Fonte: [integração Pix via Payments API](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/integration-configuration/integrate-pix).

## Campos e avaliação

| Campo | Implementação atual | Avaliação |
| --- | --- | --- |
| `transaction_amount` | Preço da categoria, no servidor | Nome e origem corretos |
| `payment_method_id` | `pix` | Correto |
| `description` | Inscrição, equipe, evento e categoria | Compatível com o guia |
| `external_reference` | UUID da tentativa | Correto; identifica a cobrança no WODZ |
| `date_of_expiration` | Prazo mínimo de 31 minutos, em ISO com fuso | Dentro da faixa documentada de 30 minutos a 30 dias |
| `payer.email` | E-mail do capitão cadastrado no WODZ | Não há mapeamento de comprador fictício; merece ajuste no teste |
| `payer.identification.type` | `CPF` | Correto |
| `payer.identification.number` | CPF informado, validado e sem pontuação | Estrutura correta; fixture controlada usada no teste foi `19119119100` |
| `application_fee` | Comissão calculada; omitida com percentual zero | Nome correto para Payments API |
| `notification_url` | Webhook configurado | Campo compatível com o guia |
| `Authorization` | Token OAuth do recebedor | Fonte correta para marketplace; a autorização efetiva desse POST permanece recusada |
| `X-Idempotency-Key` | Mesmo UUID da tentativa | Presente, conforme a documentação |

Não existe envio de `tags` nesse JSON. `test_user` é lido de `/users/me` para
validar o tipo da conta. `test_account`, `MP_TEST_MODE` e `live_mode` não são
campos usados para forçar o ambiente no corpo do pagamento. `payer.id`,
`marketplace`, `marketplace_fee` ou `test_mode` não devem ser acrescentados
por suposição. O guia marketplace usa OAuth por vendedor e distingue
`application_fee` em pagamentos de `marketplace_fee` em preferências.

Fonte: [marketplace pela Payments API](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/how-tos/integrate-marketplace).

## Classificação dos dados efetivamente salvos no checkout 8

A leitura foi somente em memória; o relatório contém apenas booleanos:

- CPF com 11 dígitos: sim.
- CPF igual à fixture `19119119100`: não. Isso não prova que seja inválido ou real.
- E-mail igual ao cadastro do capitão: sim.
- E-mail no domínio `@testuser.com`: não.
- Webhook presente: sim.

O código valida o CPF por dígitos verificadores, mas não confirma que o CPF e
o e-mail representem a mesma pessoa. Se outro responsável paga a inscrição,
o payload pode conter seu CPF com o e-mail do capitão. A documentação chama
esse contato de e-mail do comprador; a regra de produto precisa alinhar
essa origem quando o responsável é outra pessoa. Não foi comprovado que
essa combinação provoque o 401 atual.

Dados filtrados: [classificação](mercadopago-pix-payer-classification-2026-10-12.json).

## Controles executados com a mesma autorização

Todos confirmaram primeiro a mesma aplicação `4961044287054715` e vendedor
fictício ativo `3758553874`. O valor foi R$ 1,00, com nova referência e chave
por cenário. Nenhum token foi renovado ou trocado.

| Cenário | HTTP | Resultado |
| --- | --- | --- |
| E-mail fictício de teste + CPF de exemplo | 401 | `Unauthorized use of live credentials` |
| E-mail fictício de teste, sem CPF | 401 | Mesmo motivo |
| E-mail de teste + CPF + nome e sobrenome fictícios | 401 | Mesmo motivo |
| Sem objeto `payer`, controle incompleto | 500 | Sem payment ID ou QR Code; não serve como requisição válida |
| E-mail do comprador do pagamento WODZ aprovado + CPF de exemplo | 401 | Mesmo motivo |

O último cenário recuperou o e-mail do pagamento `182705002681` e só enviou o
POST após validar pagamento aprovado do mesmo recebedor, comprador distinto
e e-mail íntegro de teste, sem máscara. O e-mail permaneceu em memória e não
foi gravado em relatório. Request ID desse controle:
`37b9fbe9-c151-43aa-b635-e87ec0542843`, referência
`94094eb3-ad5c-4090-b8b9-94466da4afcc`, às 12:40:41 de Brasília.

Relatórios:

- [E-mail e CPF](mercadopago-pix-payer-testemailcpf-2026-10-12.json).
- [Somente e-mail](mercadopago-pix-payer-testemailonly-2026-10-12.json).
- [Nome e sobrenome](mercadopago-pix-payer-testnamescpf-2026-10-12.json).
- [Controle incompleto](mercadopago-pix-payer-nopayer-2026-10-12.json).
- [Comprador do pagamento aprovado](mercadopago-pix-payer-approvedbuyer-2026-10-12.json).

Não houve pagamento nem alteração de inscrições ou OAuth. Todos os POSTs
retornaram sem ID de pagamento e sem QR Code.

## O que os erros permitem concluir

A referência oficial diferencia `400 / 2198` para e-mail de teste inválido e
`400 / 2067` para identificação inválida de `401 / Unauthorized use of live
credentials`, associado à permissão de pagamento no token. Portanto, os
controles indicam que substituir o e-mail ou omitir CPF não resolve o bloqueio
de autorização nesta combinação de conta/aplicação/grant. Não demonstram que
o payload de produção foi validado integralmente pelo provedor.

Fonte: [criar pagamento](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api-payments/create-payment/post).

## Perfis, ambiente e próximos pontos a conferir

O usuário confirmou que `3758553874` foi criada com o **perfil de conta de teste
Marketplace/Integrador**, e não apenas que a aplicação usa o modelo Marketplace.
Essa mesma conta está conectada como recebedora em Recebimentos. Os papéis de
Integrador, Vendedor e Comprador precisam ser distinguidos no próximo controle.
O perfil da conta não é alterado pelo JSON Pix. Ainda não foi comprovado que
separar esses papéis resolverá o 401, mas é uma hipótese concreta de configuração
que os testes anteriores não isolaram.

O próximo cenário recomendado mantém a aplicação `4961044287054715` e seu
Client ID/Secret, com a conta `3758553874` no papel de integrador, e conecta
uma conta de teste criada como **Vendedor** em Recebimentos. O comprador de
teste continua separado. Para trocar o User ID, a implementação atual exige
**Desconectar conta → Conectar Mercado Pago**; a autorização deve ser feita
com a sessão do novo vendedor.

`PayoutAccountService.disconnect` conserva o vínculo antigo e seus tokens
cifrados, marcando-o como `DISCONNECTED`. O callback seguinte cria uma nova
conta; pagamentos antigos mantêm o `payoutAccountId` original. O controle deve
usar uma inscrição nova, sem apagar as anteriores, para não retomar um checkout
emitido com a conta anterior. Essa mudança de conexão não requer build nem
edição de credenciais da aplicação.

A separação de papéis não altera o e-mail de `payer`: ele continua vindo do
cadastro do capitão no WODZ. O controle Pix com o novo vendedor deve continuar
usando dados de comprador fictício compatíveis, como nos testes isolados desta
análise. Nenhuma conta foi desconectada ou conectada por esta análise.

Fonte: [contas de teste](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/test-accounts).

A documentação de teste genérica da Payments API pede credenciais de teste
do usuário de produção. O guia Pix Bricks também contempla emissão com
credenciais de produção de usuário fictício. Esse contexto não permite
concluir que todo `APP_USR` esteja incorreto. Trocar o OAuth global por `TEST`
afetaria o fluxo Checkout Pro que já funciona; uma configuração Pix distinta
só deve ser definida com base no fluxo oficialmente suportado para esta conta.

Fontes: [teste Payments](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-payments/integration-test/make-test-purchase)
e [Pix Bricks](https://www.mercadopago.com.br/developers/pt/docs/checkout-bricks/payment-brick/payment-submission/pix).

Também há diferença entre os e-mails documentados para Payments API e Orders
API. `test_user_br@testuser.com` pertence ao fluxo Orders mostrado no guia de
migração; não é uma substituição automática para o `/v1/payments` atual.

Fonte: [migração Payments para Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-api-orders/resources/migrate-payments-to-orders).

## Alterações desta análise

Nenhum código de produção ou credencial foi alterado. O script de diagnóstico
foi ampliado para comparar cenários e recuperar com guardas o e-mail fictício
do comprador aprovado. A análise sintática PowerShell passou; cinco cenários
foram executados com saídas filtradas. O ajuste de origem do pagador em modo
de teste foi identificado, mas não aplicado silenciosamente ao checkout.
