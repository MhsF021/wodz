package br.com.wodz.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RequestSafetyFilterTest {
    private MockHttpServletResponse request(RequestSafetyFilter filter, String path, String forgedIp, AtomicInteger reached) throws Exception {
        var request = new MockHttpServletRequest("POST",path); request.setRemoteAddr("192.0.2.1"); request.addHeader("X-Forwarded-For",forgedIp);
        var response = new MockHttpServletResponse(); filter.doFilter(request,response,(req,res) -> reached.incrementAndGet()); return response;
    }
    @Test void ipForjadoNaoContornaLimiteELogoutContinuaPermitido() throws Exception {
        var filter = new RequestSafetyFilter(); var reached = new AtomicInteger();
        for(int i=0;i<120;i++) assertEquals(200,request(filter,"/api/auth/login","192.0.2."+i,reached).getStatus());
        var rejected = request(filter,"/api/auth/login","198.51.100.2",reached);
        assertEquals(429,rejected.getStatus()); assertEquals("60",rejected.getHeader("Retry-After")); assertEquals(120,reached.get());
        assertEquals("application/problem+json;charset=UTF-8",rejected.getContentType());
        assertTrue(rejected.getContentAsString().contains("\"detail\":\"Muitas tentativas"));
        assertEquals(200,request(filter,"/api/auth/logout","198.51.100.2",reached).getStatus()); assertEquals(121,reached.get());
    }
    @Test void caminhoCodificadoNaoContornaLimiteDoContainer() throws Exception {
        var filter = new RequestSafetyFilter(); var reached = new AtomicInteger();
        for(int i=0;i<120;i++) request(filter,"/api/auth/login","",reached);
        var encoded = new MockHttpServletRequest("POST","/api/%61uth/login");
        encoded.setServletPath("/api/auth/login"); encoded.setRemoteAddr("192.0.2.1");
        var response = new MockHttpServletResponse();
        filter.doFilter(encoded,response,(req,res) -> fail("Caminho codificado contornou o limite"));
        assertEquals(429,response.getStatus());
    }
    @Test void rejeitaCorpoExcessivoAntesDeProcessar() throws Exception {
        var request = new MockHttpServletRequest("POST","/api/auth/register"); request.setContent(new byte[2_097_153]);
        var response = new MockHttpServletResponse();
        new RequestSafetyFilter().doFilter(request,response,(req,res) -> fail("Corpo excessivo chegou ao controlador"));
        assertEquals(413,response.getStatus());
    }
    @Test void limiteExpiraSemGuardarIdentificadoresIndefinidamente() throws Exception {
        long[] millis = {0};
        Clock clock = new Clock() { public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return Instant.ofEpochMilli(millis[0]);} };
        var filter = new RequestSafetyFilter(clock); var reached = new AtomicInteger();
        for(int i=0;i<120;i++) request(filter,"/api/auth/login","",reached);
        assertEquals(429,request(filter,"/api/auth/login","",reached).getStatus());
        millis[0]=60_001; assertEquals(200,request(filter,"/api/auth/login","",reached).getStatus());
    }
}
