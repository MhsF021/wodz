# Análise do HAR PIX de 12/10/2026, 01:21 (Brasília)

O HAR confirma duas falhas na criação de PIX, mantendo a inscrição em `PENDING_PAYMENT`. Ele contém somente o tráfego entre navegador e WODZ; a causa retornada pela API do Mercado Pago precisa ser consultada nos logs do backend.

## Evidências do navegador

| Operação | Horário de Brasília | Resultado |
| --- | --- | --- |
| POST de criação de checkout PIX | 01:20:31.598 | HTTP 502, 1.148 ms |
| GET dos detalhes do checkout 4 | 01:20:32.916 | HTTP 200, sem QR Code |
| Segundo POST de criação de checkout PIX | 01:21:11.344 | HTTP 502, 719 ms |

Os dois POSTs enviam JSON com exatamente `method`, `payerCpf` e `termsAccepted`. O método é `PIX` e a aceitação dos termos é `true`. O CPF está presente, tem 11 dígitos e passa pelas verificações de formato e dígitos verificadores. Seu valor foi omitido deste relatório.

As duas respostas de erro são `application/problem+json`, com título `Bad Gateway`, status `502` e mensagem: “Não foi possível consultar o Mercado Pago. Tente novamente em instantes.” Não há código de erro do provedor ou cabeçalho de correlação exposto.

As 13 leituras seguintes ou intercaladas respondem HTTP 200: duas leituras da inscrição, dez consultas de situação do checkout e uma consulta dos detalhes. A inscrição é a equipe `446`, o valor é R$ 250,00 e o checkout é `4`. Todas as leituras da situação mostram `PENDING_PAYMENT`, `lastPayment=null` e `checkoutId=4`.

Os detalhes do checkout mostram `method=PIX`, vencimento `2026-10-12T04:51:31.678342Z` (01:51:31.678342 em Brasília) e `url`, `qrCode` e `qrCodeBase64` nulos. Assim, o checkout local foi persistido, mas o HAR não demonstra uma cobrança PIX emitida no Mercado Pago.

## O que o HAR não permite concluir

Não há requisição para OAuth, callback ou API do Mercado Pago nas 15 entradas. Não aparecem o Client ID efetivamente usado, os escopos concedidos, a referência externa da cobrança ou um ID de pagamento do provedor.

O `502` é a resposta que o WODZ entregou ao navegador. Ele não prova que o Mercado Pago também tenha respondido `502`, tampouco prova que a recusa anterior `401 / Unauthorized use of live credentials` tenha se repetido. Também não demonstra CPF inválido, termos ausentes, falha de autenticação do usuário ou queda total da aplicação: os campos visíveis são válidos e as leituras responderam `200`.

Para localizar a causa, correlacionar equipe `446` / checkout `4` com os logs do backend nos instantes `2026-10-12T04:20:31.598Z` e `2026-10-12T04:21:11.344Z`. Depois, verificar qual operação falhou: obtenção/validação do OAuth, busca de pagamento já existente ou POST de emissão do PIX.

## Tratamento dos dados

O HAR original não foi copiado para o repositório. Este relatório e seu JSON associado omitem o código de acesso da inscrição, cabeçalhos de autorização, cookies, CPF, e-mail, nomes pessoais e corpos brutos. Nenhuma chamada externa foi executada e nenhum registro ou configuração foi alterado nesta análise.
