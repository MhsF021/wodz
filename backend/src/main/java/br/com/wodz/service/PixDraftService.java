package br.com.wodz.service;

import br.com.wodz.domain.CheckoutSession;
import br.com.wodz.repository.CheckoutSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Confirma a chave e o payload antes de enviar a cobrança, inclusive para recuperar uma queda do processo. */
@Service
public class PixDraftService {
    private final CheckoutSessionRepository sessions;
    public PixDraftService(CheckoutSessionRepository sessions) { this.sessions = sessions; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CheckoutSession save(CheckoutSession draft) {
        return sessions.saveAndFlush(draft);
    }
}
