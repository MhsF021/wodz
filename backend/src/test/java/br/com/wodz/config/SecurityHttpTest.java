package br.com.wodz.config;

import br.com.wodz.domain.AppUser;
import br.com.wodz.domain.Role;
import br.com.wodz.repository.AppUserRepository;
import br.com.wodz.service.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(SecurityHttpTest.TestConfig.class)
@WebAppConfiguration
class SecurityHttpTest {
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfig.class)
    static class TestConfig {
        @Bean AppProperties props() {
            return new AppProperties("https://wodz.test", List.of("https://wodz.test"),
                    new AppProperties.JwtProps("testing-secret-with-at-least-thirty-two-bytes", Duration.ofHours(12), "wodz"),
                    null, null, null, "prod", null);
        }
        @Bean AppUserRepository users() { return mock(AppUserRepository.class); }
        @Bean Endpoints endpoints() { return new Endpoints(); }
    }

    @RestController
    static class Endpoints {
        @RequestMapping({"/api/public/example", "/api/public/registrations/ABC/checkout", "/api/admin/example",
                "/api/athlete/example", "/api/auth/login", "/swagger-ui/index.html"})
        String result() { return "ok"; }
    }

    @Autowired WebApplicationContext context;
    @Autowired AppProperties props;
    @Autowired AppUserRepository users;
    @Autowired JwtEncoder encoder;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        reset(users);
    }

    private String bearer(Role role) {
        AppUser user = new AppUser();
        user.setId(7L);
        user.setRole(role);
        when(users.findById(7L)).thenReturn(Optional.of(user));
        return "Bearer " + new TokenService(encoder, props).issue(user).token();
    }

    @Test
    void autenticacaoEPapelProtegemDadosPrivados() throws Exception {
        mvc.perform(get("/api/admin/example")).andExpect(status().isUnauthorized());
        String athlete = bearer(Role.ATHLETE);
        mvc.perform(get("/api/admin/example").header("Authorization", athlete)).andExpect(status().isForbidden());
        mvc.perform(get("/api/athlete/example").header("Authorization", athlete)).andExpect(status().isOk());
        String organizer = bearer(Role.ORGANIZER);
        mvc.perform(get("/api/admin/example").header("Authorization", organizer)).andExpect(status().isOk());
        mvc.perform(get("/api/athlete/example").header("Authorization", organizer)).andExpect(status().isForbidden());
    }

    @Test
    void somenteLeituraEOperacoesPublicasExplicitasSaoAnonimas() throws Exception {
        mvc.perform(get("/api/public/example")).andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"));
        mvc.perform(post("/api/public/example")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/public/registrations/ABC/checkout")).andExpect(status().isOk());
        mvc.perform(post("/api/public/registrations/ABC/payments")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/login")).andExpect(status().isUnauthorized());
    }

    @Test
    void swaggerESimulacaoNaoSaoExpostosEmProducao() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui/index.html").header("Authorization", bearer(Role.ORGANIZER)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/public/registrations/ABC/payments/1/simulate-approval"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsAceitaOrigemExataERecusaOutraOrigemECabecalho() throws Exception {
        mvc.perform(options("/api/admin/example").header("Origin", "https://wodz.test")
                .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "authorization"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "https://wodz.test"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(options("/api/admin/example").header("Origin", "https://attacker.test")
                .header("Access-Control-Request-Method", "GET")).andExpect(status().isForbidden());
        mvc.perform(options("/api/public/example").header("Origin", "https://wodz.test")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "ngrok-skip-browser-warning"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", "ngrok-skip-browser-warning"));
        mvc.perform(options("/api/public/example").header("Origin", "https://attacker.test")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "ngrok-skip-browser-warning"))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/admin/example").header("Origin", "https://wodz.test")
                .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "X-Untrusted"))
                .andExpect(status().isForbidden());
    }
}
