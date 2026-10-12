package br.com.wodz.config;

import br.com.wodz.domain.*;
import br.com.wodz.domain.Role;
import br.com.wodz.repository.*;
import br.com.wodz.service.*;
import br.com.wodz.web.AuthController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.time.Duration;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(SessionRevocationHttpTest.Config.class)
@WebAppConfiguration
class SessionRevocationHttpTest {
    @Configuration @EnableWebMvc @EnableWebSecurity @Import({SecurityConfig.class, AuthController.class})
    static class Config {
        @Bean AppProperties props() { return new AppProperties("https://wodz.test",List.of("https://wodz.test"),
                new AppProperties.JwtProps("testing-secret-with-at-least-thirty-two-bytes",Duration.ofHours(1),"wodz"),null,null,null,"prod",null); }
        @Bean AppUserRepository users() { return mock(AppUserRepository.class); }
        @Bean AuthService auth() { return mock(AuthService.class); }
        @Bean PayoutAccountService payouts() { return mock(PayoutAccountService.class); }
        @Bean AccountService account(AppUserRepository users, PasswordEncoder encoder, PayoutAccountService payouts) {
            return new AccountService(users,mock(ChampionshipRepository.class),mock(TeamRepository.class),encoder,mock(GoogleTokenVerifier.class),payouts);
        }
    }
    @Autowired WebApplicationContext context;
    @Autowired AppUserRepository users;
    @Autowired PayoutAccountService payouts;
    @Autowired JwtEncoder encoder;
    @Autowired AppProperties props;

    @Test void tokenAnteriorNaoPodeSerReutilizadoDepoisDeSair() throws Exception {
        AppUser user = new AppUser(); user.setId(7L); user.setRole(Role.ATHLETE);
        when(users.findById(7L)).thenReturn(Optional.of(user)); when(users.lockActiveById(7L)).thenReturn(Optional.of(user));
        var tokens = new TokenService(encoder,props);
        String old = "Bearer " + tokens.issue(user).token();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(post("/api/auth/logout").header("Authorization",old)).andExpect(status().isNoContent())
                .andExpect(header().string("Clear-Site-Data","\"cache\""));
        mvc.perform(get("/api/auth/me").header("Authorization",old)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization","Bearer " + tokens.issue(user).token())).andExpect(status().isOk());
        verify(payouts).cancelPendingAuthorizations(7L);
    }
}
