import type { Role } from '../api/types';

/**
 * Cor de acento por perfil: verde elétrico para atletas (e visitantes),
 * laranja nitro para organizadores. O CSS lê o atributo data-role do <html>.
 */
export function applyRoleTheme(role: Role | null | undefined) {
  const theme = role === 'ORGANIZER' ? 'organizer' : 'athlete';
  document.documentElement.dataset.role = theme;
  const href = `/favicon-${theme}.png`;
  document.querySelectorAll<HTMLLinkElement>('link[rel="icon"], link[rel="apple-touch-icon"]').forEach((link) => {
    if (!link.href.endsWith(href)) link.href = href;
  });
}

export const logoFor = (role: Role | null | undefined) =>
  role === 'ORGANIZER' ? '/favicon-organizer.png' : '/favicon-athlete.png';

/** Logo principal (marca + "WODZ" + "CROSSFIT CHAMPIONSHIPS") na cor do perfil. */
export const brandLogoFor = (role: Role | null | undefined) =>
  role === 'ORGANIZER' ? '/logo-organizer.webp' : '/logo-athlete.webp';
