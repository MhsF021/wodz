import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { publicApi } from '../api/endpoints';

/**
 * Assina o stream SSE do campeonato e recarrega baterias/leaderboard
 * quando o organizador muda algo. O EventSource reconecta sozinho.
 */
export function useChampionshipStream(slug: string | undefined, extraKeys: string[][] = []) {
  const queryClient = useQueryClient();
  const extra = JSON.stringify(extraKeys);

  useEffect(() => {
    if (!slug || typeof EventSource === 'undefined') return;
    const source = new EventSource(publicApi.streamUrl(slug));
    const keys: string[][] = JSON.parse(extra);
    const refreshHeats = () => {
      queryClient.invalidateQueries({ queryKey: ['schedule', slug] });
      keys.forEach((key) => queryClient.invalidateQueries({ queryKey: key }));
    };
    const refreshLeaderboard = () => queryClient.invalidateQueries({ queryKey: ['leaderboard', slug] });
    source.addEventListener('heats', refreshHeats);
    source.addEventListener('leaderboard', refreshLeaderboard);
    return () => source.close();
  }, [slug, queryClient, extra]);
}
