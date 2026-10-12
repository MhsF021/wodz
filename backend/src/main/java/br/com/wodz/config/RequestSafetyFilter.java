package br.com.wodz.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** Proteção adicional para chamadas diretas à API, sem confiar em X-Forwarded-For. */
@Component
// Depois da cadeia de segurança, para conservar os headers de CORS nas respostas 413/429.
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class RequestSafetyFilter extends OncePerRequestFilter {
    private static final long MAX_BODY = 2 * 1024 * 1024;
    private final Clock clock;
    private final Map<String, Window> windows = new HashMap<>();
    private long cleanupAt;
    private record Window(long expires, int count) {}
    public RequestSafetyFilter() { this(Clock.systemUTC()); }
    RequestSafetyFilter(Clock clock) { this.clock = clock; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                               FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY) { reject(response, 413, "O conteúdo enviado excede o limite permitido"); return; }
        // O caminho servlet já foi decodificado pelo container (ex.: %61uth).
        String uri = request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath();
        boolean sensitive = !request.getMethod().equals("OPTIONS") && (
                (uri.startsWith("/api/auth/") && !uri.equals("/api/auth/logout") && !request.getMethod().equals("GET"))
                || uri.equals("/api/public/google/callback")
                || (request.getMethod().equals("POST") && uri.matches("/api/public/registrations/[^/]+/checkout")));
        if (sensitive && !allow(request.getRemoteAddr())) {
            response.setHeader("Retry-After", "60");
            reject(response, 429, "Muitas tentativas. Aguarde um minuto e tente novamente"); return;
        }
        chain.doFilter(request, response);
    }

    private synchronized boolean allow(String address) {
        long now = clock.millis();
        if (now >= cleanupAt) { windows.entrySet().removeIf(e -> e.getValue().expires <= now); cleanupAt = now + 60_000; }
        Window window = windows.get(address);
        if (window == null || window.expires <= now) {
            if (window == null && windows.size() >= 10_000) return false;
            windows.put(address, new Window(now + 60_000, 1)); return true;
        }
        if (window.count >= 120) return false;
        windows.put(address, new Window(window.expires, window.count + 1)); return true;
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status); response.setContentType("application/problem+json"); response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store"); response.setHeader("X-Content-Type-Options", "nosniff");
        response.getWriter().write("{\"status\":" + status + ",\"detail\":\"" + message + "\"}");
    }
}
