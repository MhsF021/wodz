package br.com.wodz.web;

import br.com.wodz.common.ApiException;
import br.com.wodz.service.PayoutAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PayoutAccountControllerTest {
    private static final String CALLBACK = "/api/public/mercadopago/callback";
    private static final String RESULT = "https://wodz.test/mercadopago/retorno?recebimentos=";
    private static final String STATE = "private-oauth-state";
    private static final String CODE = "private-oauth-code";
    private final PayoutAccountService accounts = mock(PayoutAccountService.class);
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new PayoutAccountController(accounts)).build();
        when(accounts.profileResultUrl(true)).thenReturn(RESULT + "connected");
        when(accounts.profileResultUrl(false)).thenReturn(RESULT + "error");
    }

    @Test
    void sucessoRedirecionaSemCodigoOuStateOAuth() throws Exception {
        redirected(mvc.perform(get(CALLBACK).param("state", STATE).param("code", CODE)), "connected");
        verify(accounts).complete(STATE, CODE);
    }

    @Test
    void recusaDeNegocioRedirecionaParaResultadoGenerico() throws Exception {
        doThrow(ApiException.unprocessable("Autorização inválida ou expirada")).when(accounts).complete(STATE, CODE);

        redirected(mvc.perform(get(CALLBACK).param("state", STATE).param("code", CODE)), "error");
        verify(accounts).complete(STATE, CODE);
    }

    @Test
    void cancelamentoDescartaCodigoEConsomeState() throws Exception {
        doThrow(ApiException.unprocessable("Autorização cancelada")).when(accounts).complete(STATE, null);

        redirected(mvc.perform(get(CALLBACK).param("state", STATE).param("code", CODE)
                .param("error", "access_denied")), "error");
        verify(accounts).complete(STATE, null);
        verify(accounts, never()).complete(STATE, CODE);
    }

    @Test
    void retornoSemStateRedirecionaMesmoSemAutenticacao() throws Exception {
        doThrow(ApiException.unprocessable("Autorização inválida ou expirada")).when(accounts).complete(null, CODE);

        redirected(mvc.perform(get(CALLBACK).param("code", CODE)), "error");
        verify(accounts).complete(null, CODE);
    }

    @Test
    void falhaDeInfraestruturaRedirecionaSemExporDetalhes() throws Exception {
        doThrow(new IllegalStateException("Falha com token privado e " + CODE)).when(accounts).complete(STATE, CODE);

        redirected(mvc.perform(get(CALLBACK).param("state", STATE).param("code", CODE)), "error");
        verify(accounts).complete(STATE, CODE);
    }

    @Test
    void retornoSemParametrosTambemRedirecionaParaErro() throws Exception {
        doThrow(ApiException.unprocessable("Autorização inválida ou expirada")).when(accounts).complete(null, null);

        redirected(mvc.perform(get(CALLBACK)), "error");
        verify(accounts).complete(null, null);
    }

    private void redirected(ResultActions response, String result) throws Exception {
        safeRedirect(response, RESULT + result);
    }

    private void safeRedirect(ResultActions response, String location) throws Exception {
        response.andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.LOCATION, location))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(""));
    }
}
