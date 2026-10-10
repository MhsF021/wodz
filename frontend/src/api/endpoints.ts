import { API_BASE, http } from './client';
import type { ChampionshipFilter } from '../lib/championshipFilters';
import type {
  AdminChampionshipSummary,
  AuthResponse,
  Category,
  CategoryInput,
  ChangePasswordInput,
  ChampionshipDetail,
  ChampionshipInput,
  ChampionshipStatus,
  ChampionshipSummary,
  Checkout,
  DeleteAccountInput,
  DisconnectGoogleInput,
  GenerateAllHeatsInput,
  GenerateHeatsInput,
  GoogleLoginResult,
  HeatStatus,
  HeatView,
  Leaderboard,
  MyHeats,
  Payment,
  PayoutAccount,
  PublicConfig,
  RegisterInput,
  RegistrationSummary,
  Role,
  ScoreEntry,
  ScoreInput,
  Team,
  TeamOverview,
  TeamRegistrationInput,
  TeamStatus,
  UpdateProfileInput,
  User,
  Workout,
  WorkoutInput,
  WorkoutScores,
} from './types';

const enc = encodeURIComponent;

export const authApi = {
  login: (email: string, password: string) => http.post<AuthResponse>('/api/auth/login', { email, password }),
  register: (input: RegisterInput) => http.post<AuthResponse>('/api/auth/register', input),
  google: (credential: string, role?: Role) => http.post<GoogleLoginResult>('/api/auth/google', { credential, role }),
  me: () => http.get<User>('/api/auth/me'),
  updateMe: (input: UpdateProfileInput) => http.put<User>('/api/auth/me', input),
  changePassword: (input: ChangePasswordInput) => http.put<void>('/api/auth/me/password', input),
  deleteMe: (input: DeleteAccountInput) => http.del('/api/auth/me', input),
  disconnectGoogle: (input: DisconnectGoogleInput) => http.del('/api/auth/me/google', input),
};

export const publicApi = {
  config: () => http.get<PublicConfig>('/api/public/config'),
  search: (q: string, state: string, status: ChampionshipFilter = 'ALL') =>
    http.get<ChampionshipSummary[]>(`/api/public/championships?${new URLSearchParams({ q, state, status })}`),
  championship: (slug: string) => http.get<ChampionshipDetail>(`/api/public/championships/${enc(slug)}`),
  schedule: (slug: string) => http.get<HeatView[]>(`/api/public/championships/${enc(slug)}/schedule`),
  leaderboard: (slug: string, categoryId?: number) =>
    http.get<Leaderboard>(
      `/api/public/championships/${enc(slug)}/leaderboard${categoryId ? `?categoryId=${categoryId}` : ''}`,
    ),
  streamUrl: (slug: string) => `${API_BASE}/api/public/championships/${enc(slug)}/stream`,
};

export const paymentApi = {
  summary: (code: string) => http.get<RegistrationSummary>(`/api/public/registrations/${enc(code)}`),
  checkout: (code: string) => http.post<Checkout>(`/api/public/registrations/${enc(code)}/checkout`),
  checkoutStatus: (code: string, checkoutId: number, paymentId?: string) =>
    http.get<RegistrationSummary>(`/api/public/registrations/${enc(code)}/checkout/${checkoutId}${paymentId ? `?paymentId=${enc(paymentId)}` : ''}`),
  status: (code: string, paymentId: number) =>
    http.get<Payment>(`/api/public/registrations/${enc(code)}/payments/${paymentId}`),
};

export const athleteApi = {
  register: (slug: string, input: TeamRegistrationInput) =>
    http.post<Team>(`/api/athlete/championships/${enc(slug)}/teams`, input),
  teams: () => http.get<Team[]>('/api/athlete/teams'),
  heats: () => http.get<MyHeats>('/api/athlete/heats'),
};

export const adminApi = {
  payoutAccount: () => http.get<PayoutAccount>('/api/admin/payout-account'),
  connectPayoutAccount: () => http.post<{ authorizationUrl: string }>('/api/admin/payout-account/connect'),
  disconnectPayoutAccount: () => http.del('/api/admin/payout-account'),
  championships: () => http.get<AdminChampionshipSummary[]>('/api/admin/championships'),
  championship: (id: number) => http.get<ChampionshipDetail>(`/api/admin/championships/${id}`),
  create: (input: ChampionshipInput) => http.post<ChampionshipDetail>('/api/admin/championships', input),
  update: (id: number, input: ChampionshipInput) => http.put<ChampionshipDetail>(`/api/admin/championships/${id}`, input),
  setRegistration: (id: number, open: boolean) =>
    http.patch<ChampionshipDetail>(`/api/admin/championships/${id}/registration`, { open }),
  setStatus: (id: number, status: ChampionshipStatus) =>
    http.patch<ChampionshipDetail>(`/api/admin/championships/${id}/status`, { status }),

  createCategory: (championshipId: number, input: CategoryInput) =>
    http.post<Category>(`/api/admin/championships/${championshipId}/categories`, input),
  updateCategory: (id: number, input: CategoryInput) => http.put<Category>(`/api/admin/categories/${id}`, input),
  deleteCategory: (id: number) => http.del(`/api/admin/categories/${id}`),

  createWorkouts: (championshipId: number, input: WorkoutInput) =>
    http.post<Workout[]>(`/api/admin/championships/${championshipId}/workouts`, input),
  updateWorkout: (id: number, input: WorkoutInput) => http.put<Workout>(`/api/admin/workouts/${id}`, input),
  deleteWorkout: (id: number) => http.del(`/api/admin/workouts/${id}`),

  teams: (championshipId: number) => http.get<TeamOverview[]>(`/api/admin/championships/${championshipId}/teams`),
  team: (id: number) => http.get<Team>(`/api/admin/teams/${id}`),
  setTeamStatus: (id: number, status: TeamStatus) => http.patch<Team>(`/api/admin/teams/${id}/status`, { status }),

  heats: (championshipId: number) => http.get<HeatView[]>(`/api/admin/championships/${championshipId}/heats`),
  generateAllHeats: (championshipId: number, input: GenerateAllHeatsInput) =>
    http.post<HeatView[]>(`/api/admin/championships/${championshipId}/heats/generate`, input),
  generateHeats: (workoutId: number, input: GenerateHeatsInput) =>
    http.post<HeatView[]>(`/api/admin/workouts/${workoutId}/heats/generate`, input),
  createHeat: (workoutId: number, input: { scheduledAt: string; teamIds: number[]; intervalMinutes?: number }) =>
    http.post<HeatView>(`/api/admin/workouts/${workoutId}/heats`, input),
  updateHeat: (id: number, input: { scheduledAt: string; teamIds?: number[] | null }) =>
    http.put<HeatView>(`/api/admin/heats/${id}`, input),
  deleteHeat: (id: number) => http.del(`/api/admin/heats/${id}`),
  startHeat: (id: number) => http.post<HeatView>(`/api/admin/heats/${id}/start?finishRunning=true`),
  setHeatStatus: (id: number, status: HeatStatus) => http.patch<HeatView>(`/api/admin/heats/${id}/status`, { status }),

  scores: (workoutId: number) => http.get<WorkoutScores>(`/api/admin/workouts/${workoutId}/scores`),
  saveScore: (workoutId: number, teamId: number, input: ScoreInput) =>
    http.put<ScoreEntry>(`/api/admin/workouts/${workoutId}/scores/${teamId}`, input),
  deleteScore: (workoutId: number, teamId: number) => http.del(`/api/admin/workouts/${workoutId}/scores/${teamId}`),
};
