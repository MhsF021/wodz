package br.com.wodz.service;

/**
 * Publicado quando baterias ou resultados mudam. Entregue aos navegadores
 * via SSE depois do commit (RealtimeService).
 */
public record ChampionshipUpdatedEvent(Long championshipId, String topic) {

    public static final String HEATS = "heats";
    public static final String LEADERBOARD = "leaderboard";
}
