import type { Role } from '../api/types';

/** Escolha do perfil (radio): usada no cadastro e no primeiro acesso com o Google. */
export function RoleChoice({
  value,
  onChange,
  name = 'role',
  legend = 'Como você vai usar o WODZ?',
}: {
  value: Role;
  onChange: (role: Role) => void;
  name?: string;
  legend?: string;
}) {
  return (
    <fieldset className="choice">
      <legend className="field__label">{legend}</legend>
      <label className="choice__option">
        <input type="radio" name={name} checked={value === 'ATHLETE'} onChange={() => onChange('ATHLETE')} />
        <span>
          <strong>Sou atleta</strong>
          <span className="choice__hint">Sou atleta e quero me inscrever em um campeonato.</span>
        </span>
      </label>
      <label className="choice__option">
        <input type="radio" name={name} checked={value === 'ORGANIZER'} onChange={() => onChange('ORGANIZER')} />
        <span>
          <strong>Organizo campeonatos</strong>
          <span className="choice__hint">Sou organizador de campeonatos (Box de crossfit, agência, etc).</span>
        </span>
      </label>
    </fieldset>
  );
}
