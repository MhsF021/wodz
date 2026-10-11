const failures: Record<string, string> = {
  invalid_state: 'Esta tentativa de autorização é inválida, expirou ou já foi utilizada. Inicie uma nova conexão.',
  missing_code: 'O Mercado Pago retornou sem concluir a autorização. Inicie uma nova conexão.',
  cancelled: 'Você cancelou a autorização no Mercado Pago. Sua conexão anterior foi mantida.',
  configuration: 'O Mercado Pago recusou a configuração da integração. A administração do WODZ precisa conferir as credenciais e o endereço de retorno.',
  token_rejected: 'O código de autorização não foi aceito pelo Mercado Pago. Ele pode ter expirado ou já ter sido utilizado. Inicie uma nova conexão.',
  authorization_rejected: 'O Mercado Pago não aceitou a autorização. Tente novamente; se persistir, contate o suporte.',
  mode_mismatch: 'A conta não é compatível com o modo de pagamento configurado. No modo de teste, conecte um vendedor de teste do Mercado Pago; em produção, conecte uma conta real.',
  account_unavailable: 'O Mercado Pago não confirmou uma conta brasileira ativa para recebimentos. Confira a situação da sua conta e tente novamente.',
  account_change: 'Desconecte a conta atual antes de conectar outra conta Mercado Pago.',
  provider_unavailable: 'Não foi possível confirmar a autorização com o Mercado Pago. Tente conectar novamente em instantes.',
  invalid_response: 'O Mercado Pago retornou uma autorização incompleta. Tente conectar novamente; se persistir, contate o suporte.',
  callback_missing: 'A integração está com um endereço de retorno incorreto. A administração do WODZ precisa corrigir a configuração do Mercado Pago.',
  unexpected: 'Não foi possível concluir a autorização. Tente novamente; se persistir, contate o suporte.',
};

const knownReason = (reason: string | null) => reason && Object.hasOwn(failures, reason) ? reason : 'unexpected';

/** Mensagens fixas: parâmetros de URL nunca viram texto da interface. */
export function payoutFailureMessage(reason: string | null) {
  return failures[knownReason(reason)];
}

/** Remove os parâmetros OAuth também quando o provedor retorna à rota errada. */
export function payoutReturnDestination(params: URLSearchParams) {
  const next = new URLSearchParams();
  if (params.has('code') || params.has('state') || params.has('error')) {
    next.set('recebimentos', 'error');
    next.set('motivo', 'callback_missing');
  } else {
    const result = params.get('recebimentos');
    next.set('recebimentos', result === 'connected' || result === 'error' ? result : 'pending');
    if (result === 'error') next.set('motivo', knownReason(params.get('motivo')));
  }
  return `/minha-conta?${next}#recebimentos`;
}
