package br.com.wodz.web;

import br.com.wodz.common.ApiException;
import br.com.wodz.config.AppProperties;
import br.com.wodz.config.SecurityConfig;
import br.com.wodz.repository.AppUserRepository;
import br.com.wodz.service.GoogleTokenVerifier;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Duration;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(GoogleRedirectControllerTest.Config.class)
@WebAppConfiguration
class GoogleRedirectControllerTest {
    static final String PATH = "/api/public/google/callback";
    static final String STATE = "67b35846-6f17-4fca-90bd-15944ccda657";
    static final String CREDENTIAL = "encoded.payload.signature";
    @Configuration @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfig.class, GoogleRedirectController.class})
    static class Config {
        @Bean AppProperties props() { return new AppProperties("https://wodz.test", List.of("https://wodz.test"),
                new AppProperties.JwtProps("testing-secret-with-at-least-thirty-two-bytes", Duration.ofHours(12), "wodz"),
                null, null, null, "prod", null); }
        @Bean AppUserRepository users() { return mock(AppUserRepository.class); }
        @Bean GoogleTokenVerifier google() { return mock(GoogleTokenVerifier.class); }
    }
    @Autowired WebApplicationContext context;
    @Autowired GoogleTokenVerifier google;
    MockMvc mvc;
    @BeforeEach void setup() { reset(google); mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }

    @Test void recebePostGoogleSemBearerEDevolvePonteProtegida() throws Exception {
        mvc.perform(post(PATH).header("Origin", "https://accounts.google.com").cookie(new Cookie("g_csrf_token", "csrf"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("g_csrf_token", "csrf")
                .param("state", STATE).param("credential", CREDENTIAL))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", containsString("script-src 'nonce-")))
                .andExpect(content().string(containsString("flow.id !== '" + STATE + "'")))
                .andExpect(content().string(containsString("location.replace('/google/retorno')")))
                .andExpect(header().doesNotExist("Location"));
        verify(google).verifyRedirect(CREDENTIAL, STATE);
    }
    @Test void rejeitaCookieAusenteOuDiferenteSemVerificarToken() throws Exception {
        for (String cookie : List.of("", "other")) {
            mvc.perform(post(PATH).cookie(new Cookie("g_csrf_token", cookie)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("g_csrf_token", "csrf").param("state", STATE).param("credential", CREDENTIAL))
                    .andExpect(status().isSeeOther()).andExpect(redirectedUrl("https://wodz.test/google/retorno?erro=confirmacao"));
        }
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("g_csrf_token", "csrf").param("state", STATE).param("credential", CREDENTIAL)).andExpect(status().isSeeOther());
        verifyNoInteractions(google);
    }
    @Test void rejeitaStateOuCredencialQuePoderiamInjetarScript() throws Exception {
        for (String credential : List.of("</script><script>alert(1)</script>", "x".repeat(8193))) {
            mvc.perform(post(PATH).cookie(new Cookie("g_csrf_token", "csrf")).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .param("g_csrf_token", "csrf").param("state", STATE).param("credential", credential)).andExpect(status().isSeeOther());
        }
        mvc.perform(post(PATH).cookie(new Cookie("g_csrf_token", "csrf")).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("g_csrf_token", "csrf").param("state", "'" + STATE).param("credential", CREDENTIAL)).andExpect(status().isSeeOther());
        verifyNoInteractions(google);
    }
    @Test void tokenInvalidoOuNonceDiferenteNaoVoltaAoNavegador() throws Exception {
        doThrow(ApiException.unprocessable("Inválido")).when(google).verifyRedirect(CREDENTIAL, STATE);
        mvc.perform(post(PATH).cookie(new Cookie("g_csrf_token", "csrf")).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("g_csrf_token", "csrf").param("state", STATE).param("credential", CREDENTIAL))
                .andExpect(status().isSeeOther()).andExpect(content().string(""))
                .andExpect(header().string("Location", not(containsString(CREDENTIAL))));
    }
    @Test void corsDoCallbackNaoAbreOutrasOrigens() throws Exception {
        mvc.perform(post(PATH).header("Origin", "https://attacker.invalid").cookie(new Cookie("g_csrf_token", "csrf"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("g_csrf_token", "csrf")
                .param("state", STATE).param("credential", CREDENTIAL)).andExpect(status().isForbidden());
        verifyNoInteractions(google);
    }
}
