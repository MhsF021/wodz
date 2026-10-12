import { Link, useNavigate } from 'react-router';
import { useQueryClient } from '@tanstack/react-query';
import { adminApi } from '../../api/endpoints';
import { ChampionshipForm } from './ChampionshipForm';

export default function NewChampionshipPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  return (
    <div className="page page--form">
      <h1 className="page-title">Novo campeonato</h1>
      <ChampionshipForm
        submitLabel="Criar campeonato"
        onSubmit={async (input) => {
          const created = await adminApi.create(input);
          queryClient.invalidateQueries({ queryKey: ['admin-championships'] });
          navigate(`/organizador/campeonatos/${created.id}?aba=categorias`);
        }}
      />
    </div>
  );
}
