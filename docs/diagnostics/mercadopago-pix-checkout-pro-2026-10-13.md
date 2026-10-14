# Pix no Checkout Pro da WODZ

Atualizado em 13/10/2026. A escolha de pagamento ocorre na página hospedada do
Mercado Pago, após o botão **Pagar no Mercado Pago**. O organizador continua
conectado por OAuth e recebe na conta vinculada. Esta alteração não muda a
configuração de comissão da aplicação.

## Implementação

- Novas preferências são criadas sem `payment_methods`, sem exclusões ou seleção
  prévia de Pix/cartão.
- Preferências antigas válidas são atualizadas no mesmo ID para remover as
  exclusões de métodos e tipos. No teste com a API, listas `[]` no PUT foram
  ignoradas. O formato `[{"id":""}]`, presente nos exemplos oficiais, removeu
  a exclusão e a WODZ retomou a preferência com sucesso.
- O frontend envia apenas `termsAccepted`. `CARD` permanece como identificador
  legado da sessão hospedada e da resposta; não determina como o atleta paga.
  O método efetivo é obtido do pagamento consultado no provedor.
- A confirmação mantém as validações de valor, moeda, referência, recebedor e
  modo financeiro. Não depende de parâmetros `approved` recebidos no navegador.
- Um Pix pendente impede criar outra preferência se a anterior não puder ser
  retomada, inclusive após vencer a preferência. O prazo da preferência não
  cancela o Pix já emitido. A tela mantém a mensagem de confirmação pendente e
  continua consultando o pagamento.

## Evidência com o provedor

Consultas autenticadas às transações já registradas no ambiente local confirmaram:

| Pagamento | Meio | Valor | Situação | Recebedor | Pagador |
|---|---|---|---|---|---|
| 183903819308 | master / credit_card | R$ 250,00 | approved / accredited | 3740570257 | 3740570267 |
| 183958881156 | account_money | R$ 460,00 | approved / accredited | 3740570257 | 3740570267 |

Os dois pagamentos pertencem à aplicação `4961044287054715`, por preferências
com `marketplace_fee=0`. Suas preferências antigas excluíam `pix`. As consultas
foram somente leitura e estão nos relatórios de baseline deste diretório.

Na inscrição de teste 455, a preferência
`3740570257-063d6ea2-c96e-4e66-b398-e43e42a42787` foi retomada pela WODZ após
a atualização, preservando a sessão 13, o valor de R$ 250,00 e o recebedor.
O pedido não continha `method`. O backend validou a resposta sem exclusões e
retornou a URL `init_point`. O navegador foi redirecionado pelo próprio Mercado
Pago para `/checkout/v1/payment/redirect/`.

Na versão final, o pedido `{"termsAccepted":true}` retornou HTTP 201 com sessão
16 e preferência `3740570257-d6f4ce8e-f5b2-460a-ba4b-e44e0b7b6334`. O GET
autenticado confirmou a referência, a aplicação, o recebedor, as três URLs de
retorno e listas sem IDs de exclusão. O navegador abriu a escolha de meios no
Mercado Pago. A tela da WODZ mostrou somente o botão de redirecionamento e o
aceite, sem seletor de cartão/Pix.

## Validação local e limites

- 64 testes focados de backend passaram: gateway, serviço de pagamento,
  assinatura e recebimento de webhook.
- 113 testes de frontend passaram; a verificação TypeScript também passou.
- As imagens de backend e frontend foram construídas com sucesso.
- A revisão independente encontrou a lacuna de Pix pendente após expiração da
  preferência; a proteção e os testes correspondentes foram implementados e
  revisados novamente sem novos achados.

A geração do QR e a aprovação de um novo Pix no checkout externo ainda não
foram confirmadas. A ausência de exclusões permite oferecer os meios elegíveis,
mas não garante a habilitação de Pix pelo Mercado Pago para a conta de teste.
Na preferência nova `3740570257-75102af2-6995-4426-bcab-ccfb1d120faa`, a lista
observada no navegador exibiu cartão, boleto e débito virtual CAIXA, sem Pix.
O resultado foi o mesmo na preferência final da sessão 16. O navegador estava
em uma sessão identificada como MATHEUS, sem validação do pagador de teste
informado na conversa. Esse teste de exibição não homologa as duas contas
especificadas pelo usuário.
Nenhum pagamento foi efetuado por esta validação.

O titular conectado localmente é `TESTUSER3041627696758533338` (ID 3740570257),
conforme `/users/me`. Ele difere do recebedor informado nesta conversa,
`TESTUSER3503622767252918820` (ID 3758553874), que corresponde ao vínculo antigo
desconectado. Após o usuário confirmar os números das transações, consultas novas
identificaram o pagador `TESTUSER2218700115689149224` (ID 3740570267) nas duas
compras e o recebedor efetivo 3740570257, também nas duas. Os relatórios de
correlação de cartão e saldo deste diretório registram esses resultados.

O usuário identificou os testes como homologação. As preferências apontam
retorno e notificações para `https://probably-power-flannels.ngrok-free.dev`.
O endereço de homologação Render registrado no README retornou HTTP 502 na
consulta pública; isso não altera a correlação das transações no provedor.
A validação prosseguiu no vínculo que já processou cartão e saldo, sem trocar
o recebedor. A sessão 17 foi criada sem escolha prévia de meio e sem exclusões
para conferir o checkout com o comprador de teste informado.

## Conferência com o comprador informado

Após a confirmação do usuário, o navegador entrou com
`TESTUSER2218700115689149224`. O login foi validado pela rotina de contas de
teste documentada pelo Mercado Pago, sem salvar senha ou efetuar pagamento.
A lista completa da preferência da sessão 17 exibiu:

- saldo fictício da conta Mercado Pago;
- Visa de teste;
- Elo de teste;
- Mastercard de teste;
- novo cartão;
- boleto;
- débito virtual CAIXA.

Pix continuou ausente. O GET autenticado confirmou que a preferência não contém
IDs de métodos ou tipos excluídos. A captura
`mercadopago-checkout-test-buyer-no-pix-2026-10-13.png` registra essa lista com
o comprador correto. Essa ausência é observada no checkout do provedor; não
é uma escolha ou restrição apresentada pela WODZ.

A documentação oficial prevê teste de Pix/offline no Checkout Pro, com geração
de pagamento `pending`, e também exige chave Pix no recebedor para exibir Pix.
Não foi confirmado se a conta de teste recebedora atual dispõe de chave ou
habilitação correspondente. Assim, não é possível atribuir a ausência a uma
causa específica nem declarar o Pix genericamente indisponível em Sandbox.
Nenhuma chave foi criada, nenhum vínculo foi substituído e nenhum valor foi
movimentado nesta conferência.

Para a análise do suporte Mercado Pago, as referências são: aplicação
`4961044287054715`, recebedor `3740570257`, comprador `3740570267`, preferência
`3740570257-b1709fce-fdb2-49fd-8c28-840617e91dd8` e os dois pagamentos de
cartão/saldo aprovados da tabela acima. A operação pretendida é Checkout Pro
Marketplace, sem comissão da plataforma, com escolha de meio exclusivamente
na página do Mercado Pago.

Na conta de teste local, o GET da preferência nova informou
`preference_expired=true` apesar de uma vigência de 20:11:56 a 20:41:55 e uma hora
remota de 20:14:45 (Brasília); o próprio checkout abriu normalmente. A integração
tolera essa inconsistência somente para conta `test_user` previamente validada,
com `expires=true`, início não futuro, prazo ainda válido e fim idêntico ao
prazo da sessão, com precisão de milissegundos. Produção mantém o bloqueio pela
flag, e datas vencidas, futuras ou divergentes continuam recusadas.

## Referências

- [Meios de pagamento no Checkout Pro](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-settings/payment-methods)
- [Exemplo oficial de preferência com IDs vazios](https://github.com/mercadopago/sdk-php/blob/master/examples/Preference/Create.php)
- [Validade do pagamento Pix](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/additional-settings/expiration-date)
- [Compras de teste](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-preferences/integration-test/test-purchases)
