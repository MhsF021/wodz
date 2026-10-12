# Checkout Pro: HAR de 12/10/2026

Fonte: `www.mercadopago.com.br_Archive [26-10-12 00-24-29].har`. Análise somente leitura. Horários em Brasília (`-03:00`). O arquivo contém 19 requisições, de **00:23:36 a 00:23:41 de 12/10**, e não contém a sessão aprovada informada pelo usuário às **23:08 de 11/10**, pagamento **183716936334**.

## Evidências

| Campo | Valor observado no checkout que falhou |
| --- | --- |
| Aplicação (`client_id`) | `6035581613565283` |
| Recebedor (`collector_id`) | `3758553874` |
| Pagador (`payer_id`) | `3740570267` |
| Preferência | `3758553874-3748fbd1-193d-4e6d-b99c-45a1b785afc8` |
| Referência da inscrição | `41a5b744-5731-4005-984f-737786211b4f` |
| Método e valor | `account_money`, R$ 250,00, `BRL` |
| Fluxo | `express_account_money` |
| Flow ID | `2faed203-a826-4e19-87de-f38ae868480f` |
| Router request ID | `e012bd37-f56d-4d03-8347-503281f5788b` |
| Erro | `FL-7fc6784a-0e83-432d-95f2-27b938a4d3c7` |
| Classificação | `unexpected_error`, `service_error`, recuperável |
| Serviço identificado | `N/A`, detalhe `0` |

Todos os status HTTP são 200 ou 204. Isso não significa pagamento aprovado: a resposta do checkout encerra em `currentStep: error`.

## Cronologia

1. **00:23:36.537** — `PUT /checkout/v1/payment/api/flow/{flowId}`. Ação `pay`, etapa anterior `review`, resposta HTTP 200 com etapa `challenge`. O componente informa explicitamente `challenge: reauth`. O formulário de submissão contém os campos de e-mail, nome e sobrenome; seus valores foram omitidos.
2. **00:23:37.837** — `POST /checkout/v1/payment/api/info`, HTTP 200. Telemetria; não é confirmação de pagamento.
3. **00:23:38.533** — `GET /checkout/v1/payment/api/reauth`, HTTP 200, corpo `{}`. O arquivo não contém uma resposta de autenticação bem-sucedida que permita atribuir o erro à senha, sessão ou comprador.
4. **00:23:39.142** — `PUT /checkout/v1/payment/api/flow/{flowId}`. Etapa anterior `challenge`, resposta HTTP 200 com etapa `error` e o erro acima. Request ID de resposta: `745b377d-5aa2-4827-bd06-ab26ca649e4e`.

Na resposta de `challenge`, o objeto oculto `payment` informa `total_paid_amount: 250` e um elemento com `payment_type: account_money` e `paid_amount: 250`. Esse objeto não contém ID de pagamento, status nem autorização. **Ele não comprova uma cobrança aprovada.**

## Diferenças que precisam ser rastreadas

- A nova aplicação fornecida ao WODZ foi `4961044287054715`, mas a preferência deste HAR pertence à aplicação anterior `6035581613565283`. É necessário conferir a configuração efetiva do contêiner e a origem/data da preferência. O HAR não mostra se ela foi criada antes da troca ou reutilizada depois.
- O recebedor é `3758553874`, o ID da nova conta Marketplace de teste fornecida pelo usuário. É necessário confirmar qual conta foi conectada em Recebimentos e comparar com o recebedor do pagamento aprovado.
- O pagador deste HAR é `3740570267`. A comparação relevante agora é com o pagamento aprovado **dentro do WODZ**, sem considerar o teste manual externo descartado pelo usuário.
- O erro ocorre logo após uma etapa de reautenticação do Mercado Pago. Problema intermitente de sessão/reauth é uma hipótese, não uma causa comprovada.
- `liveMode: true`, `productive: true` e `is_eligible: true` constam nas respostas. Esses marcadores isolados não comprovam mistura de contas reais e de teste.

## Limites do arquivo

Não há criação da preferência, requisição WODZ, entrega de webhook, login completo, resposta de pagamento aprovado nem menção ao pagamento `183716936334`. O HAR permite rastrear a falha e os IDs envolvidos, mas a comparação com o sucesso depende da consulta ao Mercado Pago e ao banco do WODZ.

O [relatório JSON](mercadopago-har-2026-10-12.json) inclui a cronologia e os campos permitidos. Ambos os relatórios omitem cookies, tokens, chaves, identificadores de sessão/dispositivo, dados pessoais do pagador, QR Code, queries privadas e corpos completos.
