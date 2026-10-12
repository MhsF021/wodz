# PIX às 01:20 e 01:21: correlação completa — 12/10/2026

O HAR, os logs, o banco, a API e o JAR em execução foram correlacionados.
**A causa imediata dos dois HTTP 502 é uma recusa HTTP 401 do Mercado Pago
na criação do PIX. O backend em execução ainda contém o fluxo OAuth antigo.**

## Cadeia das duas falhas

| Tentativa, Brasília | HAR WODZ | Log de emissão | Resposta do provedor |
| --- | --- | --- | --- |
| 01:20:31.598 | POST checkout PIX, 502 em 1,148 s | 01:20:31.690, checkout 4 | 401 às 01:20:32.660 |
| 01:21:11.344 | POST checkout PIX, 502 em 0,719 s | 01:21:11.590, checkout 4 | 401 às 01:21:11.975 |

Os request IDs do provedor são, respectivamente:

- `6ad74bae-3463-44a0-8fc6-e6b9b244e452`;
- `3952a2df-0948-47ca-ad81-bcaf722732cd`.

Ambas usam a referência `ae4801a2-bce6-475d-a822-364ceafe69b5`, a mesma chave
de idempotência e o mesmo checkout 4 da equipe 446. A segunda emissão retomou
o rascunho cifrado e preservou o vencimento, não criou outra referência.

A aplicação, conta recebedora e valor são `4961044287054715`, `3758553874`
e R$ 250,00. Método PIX e aceite são enviados corretamente. O CPF do HAR passa
pelo formato e pelos dígitos verificadores. O prazo era de 31 minutos na primeira
emissão e aproximadamente 30 minutos e 20 segundos na segunda, dentro da margem
mínima de retransmissão do WODZ.

O erro foi registrado em `operation=create action=failed`. Portanto, a falha
não ocorreu apenas na busca prévia ou na leitura de um QR existente: o POST
para criar o pagamento recebeu 401. O Gateway converte esse erro em 502 para
o navegador, usando a mensagem genérica no código que está implantado.

O HAR mostra GET dos detalhes com HTTP 200 e QR/Copia e Cola nulos. Isso
significa que o checkout local foi salvo, não que o Mercado Pago emitiu um PIX.
Uma consulta financeira pela referência retornou **zero pagamentos**.

## Motivo específico confirmado em controle isolado

Às 01:30 de Brasília, com o mesmo OAuth vigente, a conta e a aplicação foram
consultadas antes de uma emissão fictícia isolada de R$ 1,00. Ambas as leituras
retornaram HTTP 200, confirmando conta brasileira de teste ativa, aplicação
ativa/não bloqueada e PKCE habilitado.

O POST Pix isolado, com dados fictícios válidos e prazo de 31 minutos, retornou
HTTP 401 com `error=unauthorized` e mensagem:

```text
Unauthorized use of live credentials
```

Sem ID de pagamento ou QR. Request ID `ee726d02-0c9e-4485-bee7-6317003be19d`;
referência `a7015d2d-77b9-462d-bd81-ea461b7c50c1`. Não foi pago nem ligado a
qualquer inscrição. Esse controle captura o motivo com o mesmo grant; os logs
antigos das duas solicitações originais não guardam seus corpos de resposta.

A [referência oficial da Payments API](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-api-payments/create-payment/post)
associa esse 401 às permissões/escopo necessários para criar pagamentos.
A leitura da conta e o sucesso do Checkout Pro não comprovam permissão para
essa operação diferente. Não se afirma que o CPF, o webhook ou o ngrok tenham
causado a recusa de autorização.

## O código novo não está no contêiner

Foi lido temporariamente o JAR `/app/app.jar` do backend em execução e
inspecionadas suas classes. O arquivo temporário foi removido após a leitura.
Nenhum contêiner foi alterado ou reiniciado.

| Marcador da classe em execução | Resultado |
| --- | --- |
| Reconhecimento `unauthorized_live_credentials` no Gateway | Ausente |
| Log `OAuth do Mercado Pago confirmado` com scopes | Ausente |
| Constante `AUTHORIZATION_SCOPE` com `read write offline_access` | Presente |

As versões salvas no código fonte apresentam o contrário: tratam o 401 e
registram os escopos conhecidos, sem fixar scope na autorização. Portanto,
o novo HAR não avaliou a alteração preparada anteriormente.

O backend continua iniciado às 00:46 de 12/10. O vínculo OAuth foi criado
às 00:56:06 e não apresenta renovação posterior. Mesmo atualizar o contêiner
não muda os escopos de uma autorização já concedida; é necessário concluir
uma autorização nova pelo navegador em Recebimentos.

Essa descoberta explica a mensagem genérica persistente e a repetição do
mesmo fluxo. A autorização da Payments API ainda precisa ser verificada após
o código novo e o novo grant; a remoção do override de scope não garante,
por si só, que o provedor concederá a permissão necessária.

## Próxima execução

No WSL, dentro de `/mnt/c/wodz`, executar:

```bash
docker compose up -d --build --force-recreate backend
```

Esse comando atualiza o backend preservando o volume PostgreSQL. Depois,
usar **Renovar autorização** em Recebimentos, na mesma conta e aplicação
que estão aprovando Cartão / Outros. Após concluir o retorno, o log deverá
mostrar `OAuth do Mercado Pago confirmado` e os scopes conhecidos retornados.

```bash
docker compose logs --since 10m --no-color --timestamps backend \
  | grep -Ei 'OAuth do Mercado Pago confirmado|Mercado Pago PIX'
```

Gerar um PIX novo em outra inscrição de teste ou após o vencimento do checkout
atual. Se continuar a recusa, os scopes efetivos e o novo request ID permitirão
confirmar com o Mercado Pago a permissão Payments/Pix desse grant e aplicação.

## Preservação e artefatos

O pagamento `182705002681` continua aprovado/accredited na API. O banco
mantém `APPROVED`, equipe 443 `CONFIRMED` e valor R$ 250,00. O checkout PIX 4
continua pendente, sem ID externo. Nenhum registro foi excluído, nenhum OAuth
foi renovado por esta análise e nenhuma inscrição foi paga ou confirmada
artificialmente.

- [Análise apenas do HAR](mercadopago-pix-har-0121-2026-10-12.md).
- [Dados filtrados do HAR](mercadopago-pix-har-0121-2026-10-12.json).
- [Consultas financeiras](mercadopago-pix-har-0121-api-2026-10-12.json).
- [Controle fictício](mercadopago-pix-probe-0121-2026-10-12.json).

Os relatórios omitem tokens, cookies, CPF, e-mail, códigos de acesso da inscrição,
QR Code e corpos brutos. O HAR original não foi copiado para o repositório.
