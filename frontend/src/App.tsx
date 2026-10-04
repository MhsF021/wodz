import { Link, Route, Routes } from 'react-router';
import { Layout } from './components/Layout';
import { RequireRole } from './auth/RequireRole';
import HomePage from './pages/HomePage';
import ChampionshipPage from './pages/ChampionshipPage';
import LoginPage from './pages/LoginPage';
import SignupPage from './pages/SignupPage';
import TeamRegistrationPage from './pages/TeamRegistrationPage';
import PaymentPage from './pages/PaymentPage';
import MyTeamsPage from './pages/MyTeamsPage';
import MyProfilePage from './pages/MyProfilePage';
import AdminHome from './pages/admin/AdminHome';
import NewChampionshipPage from './pages/admin/NewChampionshipPage';
import AdminChampionshipPage from './pages/admin/AdminChampionshipPage';

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<HomePage />} />
        <Route path="c/:slug" element={<ChampionshipPage />} />
        <Route
          path="c/:slug/inscricao"
          element={
            <RequireRole role="ATHLETE">
              <TeamRegistrationPage />
            </RequireRole>
          }
        />
        <Route path="pagamento/:code" element={<PaymentPage />} />
        <Route path="entrar" element={<LoginPage />} />
        <Route path="cadastro" element={<SignupPage />} />
        <Route
          path="minhas-equipes"
          element={
            <RequireRole role="ATHLETE">
              <MyTeamsPage />
            </RequireRole>
          }
        />
        <Route
          path="minha-conta"
          element={
            <RequireRole>
              <MyProfilePage />
            </RequireRole>
          }
        />
        <Route
          path="organizador"
          element={
            <RequireRole role="ORGANIZER">
              <AdminHome />
            </RequireRole>
          }
        />
        <Route
          path="organizador/campeonatos/novo"
          element={
            <RequireRole role="ORGANIZER">
              <NewChampionshipPage />
            </RequireRole>
          }
        />
        <Route
          path="organizador/campeonatos/:id"
          element={
            <RequireRole role="ORGANIZER">
              <AdminChampionshipPage />
            </RequireRole>
          }
        />
        <Route
          path="*"
          element={
            <div className="page page--narrow">
              <h1 className="page-title">Página não encontrada</h1>
              <p className="lead">O endereço pode ter mudado ou o campeonato não está mais publicado.</p>
              <Link className="btn btn--secondary" to="/">
                Ver campeonatos
              </Link>
            </div>
          }
        />
      </Route>
    </Routes>
  );
}
