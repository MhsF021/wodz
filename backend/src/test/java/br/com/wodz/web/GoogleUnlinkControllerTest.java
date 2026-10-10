package br.com.wodz.web;

import br.com.wodz.common.ApiException;
import br.com.wodz.common.GlobalExceptionHandler;
import br.com.wodz.config.AppProperties;
import br.com.wodz.config.SecurityConfig;
import br.com.wodz.domain.AppUser;
import br.com.wodz.domain.Role;
import br.com.wodz.dto.AuthDtos.UnlinkGoogleRequest;
import br.com.wodz.repository.AppUserRepository;
import br.com.wodz.service.AccountService;
import br.com.wodz.service.AuthService;
import br.com.wodz.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(GoogleUnlinkControllerTest.TestConfig.class)
@WebAppConfiguration
class GoogleUnlinkControllerTest {
    private static final String PATH = "/api/auth/me/google";

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({SecurityConfig.class, AuthController.class, GlobalExceptionHandler.class})
    static class TestConfig {
        @Bean AppProperties props() {
            return new AppProperties("https://wodz.test", List.of("https://wodz.test"),
                    new AppProperties.JwtProps("testing-secret-with-at-least-thirty-two-bytes", Duration.ofHours(12), "wodz"),
                    null, null, null, "prod", null);
        }
        @Bean AppUserRepository users() { return mock(AppUserRepository.class); }
        @Bean AuthService auth() { return mock(AuthService.class); }
        @Bean AccountService account() { return mock(AccountService.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired AppProperties props;
    @Autowired AppUserRepository users;
    @Autowired JwtEncoder encoder;
    @Autowired AccountService account;
    private MockMvc mvc;
    private String authorization;

    @BeforeEach
    void setup() {
        reset(users, account);
        AppUser user = new AppUser();
        user.setId(7L);
        user.setRole(Role.ATHLETE);
        user.setGoogleSub("google-123");
        when(users.findById(7L)).thenReturn(Optional.of(user));
        authorization = "Bearer " + new TokenService(encoder, props).issue(user).token();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void exigeAutenticacaoAntesDeDesvincular() throws Exception {
        mvc.perform(delete(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"googleCredential\":\"credential\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(account);
    }

    @Test
    void exigeCredencialGoogleNaoVazia() throws Exception {
        for (String payload : List.of("{}", "{\"googleCredential\":\" \"}")) {
            mvc.perform(delete(PATH).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON).content(payload))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("errors.googleCredential").exists());
        }
        verifyNoInteractions(account);
    }

    @Test
    void encaminhaIdentidadeAutenticadaECredencialRetornando204() throws Exception {
        mvc.perform(delete(PATH).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content("{\"googleCredential\":\"credential\"}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(account).unlinkGoogle(7L, new UnlinkGoogleRequest("credential"));
    }

    @Test
    void preservaConflitoDeInscricoesAtivasSemIndicarSucesso() throws Exception {
        doThrow(ApiException.conflict("Campeonato ativo")).when(account).unlinkGoogle(7L, new UnlinkGoogleRequest("credential"));
        mvc.perform(delete(PATH).header("Authorization", authorization).contentType(MediaType.APPLICATION_JSON)
                .content("{\"googleCredential\":\"credential\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("detail").value("Campeonato ativo"));
    }
}
