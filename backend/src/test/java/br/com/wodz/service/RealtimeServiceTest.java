package br.com.wodz.service;

import br.com.wodz.common.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RealtimeServiceTest {
    @RestController
    static class StreamController {
        final RealtimeService service = new RealtimeService();
        final ArrayList<SseEmitter> connected = new ArrayList<>();
        @GetMapping("/stream/{id}") SseEmitter stream(@PathVariable Long id) {
            SseEmitter emitter = service.subscribe(id); connected.add(emitter); return emitter;
        }
    }

    @Test void limitePorEventoEGlobalLiberaVagasAoEncerrarConexoes() throws Exception {
        var controller = new StreamController();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        var requests = new ArrayList<MvcResult>();
        for (int group = 1; group <= 4; group++) {
            for (int i = 0; i < 256; i++) {
                requests.add(mvc.perform(get("/stream/" + group)).andExpect(request().asyncStarted()).andReturn());
            }
            mvc.perform(get("/stream/" + group)).andExpect(status().isTooManyRequests());
        }
        mvc.perform(get("/stream/5")).andExpect(status().isTooManyRequests());
        // Simula o encerramento real no MVC: o callback libera a cota global e do evento.
        for (int i = 0; i < 256; i++) {
            controller.connected.get(i).complete();
            mvc.perform(asyncDispatch(requests.get(i))).andExpect(status().isOk());
        }
        mvc.perform(get("/stream/1")).andExpect(request().asyncStarted());
        mvc.perform(get("/stream/5")).andExpect(request().asyncStarted());
        assertDoesNotThrow(controller.service::heartbeat);
        assertDoesNotThrow(() -> controller.service.onChampionshipUpdated(new ChampionshipUpdatedEvent(5L,"heats")));
    }
}
