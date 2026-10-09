import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation } from 'react-router';
import { Menu, X } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { firstName } from '../lib/format';
import { applyRoleTheme, logoFor } from '../lib/theme';
import { Footer } from './Footer';
import { ActivityIndicator } from './ActivityIndicator';

export function Layout() {
  const { user, logout, loading } = useAuth();
  const { pathname, hash } = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuButton = useRef<HTMLButtonElement>(null);

  useEffect(() => applyRoleTheme(user?.role), [user?.role]);
  useEffect(() => {
    if (!hash) return;
    const frame = window.requestAnimationFrame(() => {
      document.getElementById(hash.slice(1))?.scrollIntoView({ block: 'start' });
    });
    return () => window.cancelAnimationFrame(frame);
  }, [pathname, hash, user]);

  // No celular o menu fecha ao trocar de página e com Esc.
  useEffect(() => setMenuOpen(false), [pathname]);
  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return;
      setMenuOpen(false);
      menuButton.current?.focus();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [menuOpen]);

  return (
    <>
      <a href="#conteudo" className="skip-link">
        Pular para o conteúdo
      </a>
      <header className="topbar">
        <ActivityIndicator initializing={loading} />
        <div className="topbar__inner">
          <Link to="/" className="wordmark" aria-label="WODZ, página inicial">
            <img className="wordmark__logo" src={logoFor(user?.role)} alt="" width={32} height={32} />
            WODZ
          </Link>
          <button
            ref={menuButton}
            type="button"
            className="topbar__menu"
            aria-expanded={menuOpen}
            aria-controls="menu-principal"
            onClick={() => setMenuOpen((open) => !open)}
          >
            {menuOpen ? <X size={22} aria-hidden /> : <Menu size={22} aria-hidden />}
            <span className="sr-only">{menuOpen ? 'Fechar menu' : 'Abrir menu'}</span>
          </button>
          <nav id="menu-principal" className={`topnav${menuOpen ? ' is-open' : ''}`} aria-label="Principal">
            <NavLink to="/" end>
              Campeonatos
            </NavLink>
            {user?.role === 'ATHLETE' && <NavLink to="/minhas-equipes">Minhas equipes</NavLink>}
            {user?.role === 'ORGANIZER' && <NavLink to="/organizador">Painel do organizador</NavLink>}
            {user ? (
              <>
                <NavLink to="/minha-conta" className="topnav__greeting" aria-label={`Olá, ${firstName(user.name)}: minha conta`}>
                  Olá, {firstName(user.name)}
                </NavLink>
                <button type="button" className="topnav__button" onClick={logout}>
                  Sair
                </button>
              </>
            ) : (
              <NavLink to="/entrar">Entrar</NavLink>
            )}
          </nav>
        </div>
      </header>
      <main id="conteudo" className="main" tabIndex={-1}>
        <Outlet />
      </main>
      <Footer />
    </>
  );
}
