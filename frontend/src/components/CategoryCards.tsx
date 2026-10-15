import { useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { ArrowUpRight, Users } from 'lucide-react';
import type { Category, CategoryGender, ChampionshipDetail, Workout } from '../api/types';
import { capLabel, money, workoutTypeLabel } from '../lib/format';
import { categoryComposition } from '../lib/categoryComposition';
import { Dialog } from './Dialog';
import { MovementStandardsTable } from './MovementStandardsTable';

function teamLabel(category: Category) {
  return category.teamSize === 1 ? 'Individual' : category.teamSize === 2 ? 'Dupla' : `${category.teamSize} atletas`;
}

const FILTERS: { id: CategoryGender | 'ALL'; label: string }[] = [
  { id: 'ALL', label: 'Todos' },
  { id: 'MALE', label: 'Masculino' },
  { id: 'FEMALE', label: 'Feminino' },
  { id: 'MIXED', label: 'Misto' },
];

interface OrganizerDetails {
  organizer?: boolean;
  filterActions?: ReactNode;
  renderWorkoutActions?: (workout: Workout) => ReactNode;
  renderWorkoutEditor?: (workout: Workout) => ReactNode;
}

export function CategoryCards({ championship, organizer = false, filterActions, renderWorkoutActions, renderWorkoutEditor, onCategoryClose }: {
  championship: ChampionshipDetail;
  onCategoryClose?: () => void;
} & OrganizerDetails) {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [filter, setFilter] = useState<CategoryGender | 'ALL'>('ALL');
  const selected = championship.categories.find(c => c.id === selectedId);
  const visible = championship.categories.filter(c => filter === 'ALL' || c.genderPolicy === filter);
  return <div className="overview">
    {!organizer && championship.description && <p className="overview__description">{championship.description}</p>}
    <div className="category-cards__intro"><h2 className="section-title">Categorias e provas</h2><p>Selecione uma categoria para conferir as cargas, os movimentos e as provas.</p></div>
    <div className="champ-filters" role="group" aria-label="Filtrar categorias por composição">
      {FILTERS.map(item => <button key={item.id} type="button" className="champ-filter" aria-pressed={filter === item.id}
        onClick={() => setFilter(item.id)}>{item.label}</button>)}
      {organizer && filterActions && <div className="champ-filters__actions">{filterActions}</div>}
    </div>
    {championship.categories.length > 0 && <p className="category-cards__count" role="status">
      {visible.length ? `${visible.length} categoria${visible.length === 1 ? '' : 's'}` : 'Nenhuma categoria encontrada para este filtro.'}
    </p>}
    {!championship.categories.length && <p className="note">As categorias serão divulgadas em breve.</p>}
    <ul className="category-cards">
      {visible.map(category => <li key={category.id}>
        <article className={`category-card category-card--${category.genderPolicy?.toLowerCase() ?? 'pending'}`}>
          <div className="category-card__heading"><span className="category-card__format"><Users size={14} aria-hidden /> {teamLabel(category)}</span>{!organizer && <span className="category-card__price">{Number(category.price) === 0 ? 'Gratuita' : `${money(category.price)} / atleta`}</span>}</div>
          <h3><button type="button" className="category-card__open" aria-haspopup="dialog" onClick={() => setSelectedId(category.id)}>
            {category.name}<ArrowUpRight size={20} aria-hidden /></button></h3>
          <p className="category-card__composition">{categoryComposition(category)}</p>
          <div className="category-card__footer"><span>{category.workouts.length} prova{category.workouts.length === 1 ? '' : 's'}</span><span>Ver detalhes e provas <ArrowUpRight size={14} aria-hidden /></span></div>
        </article>
      </li>)}
    </ul>
    {selected && <CategoryDetails key={selected.id} category={selected} championship={championship} organizer={organizer}
      renderWorkoutActions={renderWorkoutActions} renderWorkoutEditor={renderWorkoutEditor}
      onClose={() => { setSelectedId(null); onCategoryClose?.(); }} />}
  </div>;
}

export function CategoryDetails({ category, championship, onClose, organizer = false, renderWorkoutActions, renderWorkoutEditor }: {
  category: Category; championship: ChampionshipDetail; onClose: () => void;
} & OrganizerDetails) {
  return <Dialog title={category.name} subtitle={championship.name} closeLabel="Fechar detalhes da categoria" onClose={onClose}>
    <dl className={`category-details__facts${organizer ? ' category-details__facts--organizer' : ''}`}>
      <div><dt>Formato</dt><dd>{teamLabel(category)} · {category.teamSize} atleta{category.teamSize > 1 ? 's' : ''}</dd></div>
      <div><dt>Composição</dt><dd>{categoryComposition(category)}</dd></div>
      {!organizer && <div><dt>Inscrição por atleta</dt><dd>{Number(category.price) === 0 ? 'Gratuita' : money(category.price)}</dd></div>}
      {!organizer && Number(category.price) > 0 && <div><dt>Total da equipe</dt><dd>{category.teamSize} × {money(category.price)} = {money(Number(category.price) * category.teamSize)}</dd></div>}
    </dl>
    {category.description && <p className="category-details__description">{category.description}</p>}
    <section className="category-details__section"><h3>Cargas e movimentos</h3><MovementStandardsTable category={category} /></section>
    <section className="category-details__section"><h3>Provas da categoria</h3>
      {!category.workouts.length ? <p className="note">As provas desta categoria serão divulgadas em breve.</p> : <ol className="category-details__workouts">
        {category.workouts.map(workout => <li key={workout.id}>{(organizer ? renderWorkoutEditor?.(workout) : null) ?? <><span className="category-details__eyebrow">Prova {workout.number}</span>
          <h4>{workout.name}</h4><p className="note">{workoutTypeLabel[workout.type]}{capLabel(workout.timeCapSeconds) ? ` · Cap ${capLabel(workout.timeCapSeconds)}` : ''}</p>
          {workout.repsPerRound != null && <p className="note">{workout.repsPerRound} repetições por round</p>}
          {workout.maxReps != null && <p className="note">{workout.maxReps} repetições no total</p>}
          {workout.hasTiebreak && <p className="note">Com tiebreak</p>}
          <p className="category-details__description">{workout.description || 'A descrição desta prova será divulgada pela organização.'}</p>
          {organizer && renderWorkoutActions?.(workout)}
          </>}
        </li>)}
      </ol>}
    </section>
    {!organizer && championship.registrationOpen && <Link className="btn btn--primary category-details__signup" to={`/c/${championship.slug}/inscricao`}>Ir para inscrição</Link>}
  </Dialog>;
}
