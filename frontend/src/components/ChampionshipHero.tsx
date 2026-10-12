import type { ReactNode } from 'react';
import { EventImage } from './EventImage';

export function ChampionshipHero({ name, cover, profile, preview = false, children, actions }: {
  name: string; cover?: string | null; profile?: string | null; preview?: boolean;
  children?: ReactNode; actions?: ReactNode;
}) {
  const Title = preview ? 'h3' : 'h1';
  return <header className={`champ-hero${preview ? ' champ-hero--preview' : ''}`}>
    <EventImage src={cover} name={name} cover />
    <div className="champ-hero__content">
      <EventImage src={profile} name={name} profile />
      <div className="champ-hero__identity"><Title className="champ-head__title">{name}</Title>{children}</div>
      {actions && <div className="champ-head__actions">{actions}</div>}
    </div>
  </header>;
}
