export type Role = 'ORGANIZER' | 'ATHLETE';
export type ChampionshipStatus = 'DRAFT' | 'PUBLISHED' | 'IN_PROGRESS' | 'FINISHED' | 'CANCELLED';
export type ScoringSystem = 'POINTS_TABLE' | 'RANK_SUM';
export type WorkoutType = 'FOR_TIME' | 'AMRAP' | 'MAX_LOAD' | 'MAX_REPS';
export type TeamStatus = 'PENDING_PAYMENT' | 'CONFIRMED' | 'CANCELLED';
export type PaymentMethod = 'PIX' | 'CREDIT_CARD' | 'BOLETO';
export type PaymentStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED' | 'REFUNDED';
export type HeatStatus = 'UPCOMING' | 'IN_PROGRESS' | 'FINISHED';
export type ScoreStatus = 'VALID' | 'CAPPED' | 'DNS' | 'DQ';
export type Gender = 'M' | 'F';
export type ShirtSize = 'PP' | 'P' | 'M' | 'G' | 'GG' | 'XGG';

export interface User {
  id: number;
  name: string;
  email: string;
  role: Role;
  phone?: string | null;
  /** Falso para quem só entra com o Google. */
  hasPassword: boolean;
  googleLinked: boolean;
}

export interface AuthResponse {
  token: string;
  expiresAt: string;
  user: User;
}

export interface RegisterInput {
  name: string;
  email: string;
  password: string;
  role: Role;
  phone?: string;
}

/** currentPassword só é exigida quando o e-mail muda. */
export interface UpdateProfileInput {
  name: string;
  email: string;
  phone?: string;
  currentPassword?: string;
}

/** Sem senha ainda (conta criada com o Google), currentPassword fica de fora. */
export interface ChangePasswordInput {
  currentPassword?: string;
  newPassword: string;
}

/** Com senha, confirma com ela; conta só com Google, com um novo login no Google. */
export interface DeleteAccountInput {
  password?: string;
  googleCredential?: string;
}

export interface PendingGoogleSignup {
  email: string;
  name: string;
}

/** Ou entra (auth), ou é o primeiro acesso e falta escolher o perfil (pending). */
export interface GoogleLoginResult {
  auth?: AuthResponse | null;
  pending?: PendingGoogleSignup | null;
}

export interface ChampionshipSummary {
  id: number;
  slug: string;
  name: string;
  venueName: string;
  city: string;
  state: string;
  startDate: string;
  endDate: string;
  status: ChampionshipStatus;
  registrationOpen: boolean;
  bannerUrl?: string | null;
}

export interface AdminChampionshipSummary {
  id: number;
  slug: string;
  name: string;
  venueName: string;
  city: string;
  state: string;
  startDate: string;
  endDate: string;
  status: ChampionshipStatus;
  registrationOpen: boolean;
  categories: number;
  teamsConfirmed: number;
  teamsPending: number;
  revenue: number;
}

export interface Workout {
  id: number;
  categoryId: number;
  number: number;
  name: string;
  description?: string | null;
  type: WorkoutType;
  timeCapSeconds?: number | null;
  repsPerRound?: number | null;
  hasTiebreak: boolean;
}

export interface Category {
  id: number;
  name: string;
  description?: string | null;
  teamSize: number;
  maxTeams?: number | null;
  price: number;
  displayOrder: number;
  slotsLeft?: number | null;
  workouts: Workout[];
}

export interface ChampionshipDetail {
  id: number;
  slug: string;
  name: string;
  description?: string | null;
  venueName: string;
  address?: string | null;
  city: string;
  state: string;
  startDate: string;
  endDate: string;
  status: ChampionshipStatus;
  registrationOpen: boolean;
  registrationFlag: boolean;
  /** Já é o dia de início (horário de Brasília) ou depois: inscrições fechadas. */
  started: boolean;
  registrationOpensAt?: string | null;
  registrationClosesAt?: string | null;
  scoringSystem: ScoringSystem;
  pointsFirstPlace: number;
  pointsStep: number;
  bannerUrl?: string | null;
  categories: Category[];
}

export interface ChampionshipInput {
  name: string;
  description?: string | null;
  venueName: string;
  address?: string | null;
  city: string;
  state: string;
  startDate: string;
  endDate: string;
  registrationOpensAt?: string | null;
  registrationClosesAt?: string | null;
  scoringSystem: ScoringSystem;
  pointsFirstPlace: number;
  pointsStep: number;
  bannerUrl?: string | null;
}

export interface CategoryInput {
  name: string;
  description?: string | null;
  teamSize: number;
  maxTeams?: number | null;
  price: number;
  displayOrder?: number;
}

export interface WorkoutInput {
  categoryIds?: number[];
  number: number;
  name: string;
  description?: string | null;
  type: WorkoutType;
  timeCapSeconds?: number | null;
  repsPerRound?: number | null;
  hasTiebreak: boolean;
}

export interface CategoryRef {
  id: number;
  name: string;
  price: number;
}

export interface ChampionshipRef {
  id: number;
  slug: string;
  name: string;
  startDate: string;
  endDate: string;
  venueName: string;
  city: string;
  state: string;
}

export interface Lane {
  lane: number;
  teamId: number;
  teamName: string;
  boxName?: string | null;
  judgeName?: string | null;
}

export interface HeatView {
  heatId: number;
  heatNumber: number;
  scheduledAt: string;
  status: HeatStatus;
  startedAt?: string | null;
  finishedAt?: string | null;
  workoutId: number;
  workoutNumber: number;
  workoutName: string;
  workoutType: WorkoutType;
  timeCapSeconds?: number | null;
  categoryId: number;
  categoryName: string;
  championshipId: number;
  championshipSlug: string;
  championshipName: string;
  lanes: Lane[];
}

export interface ResultCell {
  status: ScoreStatus;
  placement?: number | null;
  points?: number | null;
  display?: string | null;
}

export interface LeaderboardRow {
  rank: number;
  teamId: number;
  teamName: string;
  boxName?: string | null;
  totalPoints: number;
  workoutsCompleted: number;
  firstPlaces: number;
  results: Record<string, ResultCell>;
}

export interface WorkoutRef {
  id: number;
  number: number;
  name: string;
  type: WorkoutType;
}

export interface Leaderboard {
  scoringSystem: ScoringSystem;
  category: CategoryRef | null;
  categories: CategoryRef[];
  workouts: WorkoutRef[];
  rows: LeaderboardRow[];
}

export interface MemberInput {
  fullName: string;
  email: string;
  cpf: string;
  birthDate: string;
  gender: Gender | '';
  shirtSize: ShirtSize | '';
}

export interface Member {
  id: number;
  fullName: string;
  email: string;
  cpf: string;
  birthDate: string;
  gender: Gender;
  shirtSize?: ShirtSize | null;
  captain: boolean;
}

export interface TeamRegistrationInput {
  categoryId: number;
  name: string;
  boxName?: string;
  city?: string;
  members: Array<Omit<MemberInput, 'gender' | 'shirtSize'> & { gender: Gender; shirtSize: ShirtSize | null }>;
}

export interface Team {
  id: number;
  name: string;
  boxName?: string | null;
  city?: string | null;
  status: TeamStatus;
  registrationCode: string;
  createdAt: string;
  confirmedAt?: string | null;
  championship: ChampionshipRef;
  category: CategoryRef;
  members: Member[];
}

export interface TeamOverview {
  teamId: number;
  teamName: string;
  boxName?: string | null;
  city?: string | null;
  status: TeamStatus;
  registrationCode: string;
  createdAt: string;
  confirmedAt?: string | null;
  categoryId: number;
  categoryName: string;
  price: number;
  captainName: string;
  captainEmail: string;
  captainPhone?: string | null;
  memberCount: number;
  memberNames?: string | null;
  lastPaymentStatus?: PaymentStatus | null;
  lastPaymentMethod?: PaymentMethod | null;
  paidAt?: string | null;
}

export interface MyHeats {
  teamIds: number[];
  heats: HeatView[];
}

export interface Payment {
  id: number;
  method: PaymentMethod;
  status: PaymentStatus;
  statusDetail?: string | null;
  amount: number;
  pixQrCode?: string | null;
  pixQrCodeBase64?: string | null;
  boletoUrl?: string | null;
  boletoBarcode?: string | null;
  cardLastDigits?: string | null;
  installments?: number | null;
  expiresAt?: string | null;
  paidAt?: string | null;
  createdAt?: string | null;
}

export interface RegistrationSummary {
  code: string;
  teamId: number;
  teamName: string;
  championshipName: string;
  championshipSlug: string;
  categoryName: string;
  amount: number;
  teamStatus: TeamStatus;
  /** Depois do início não dá mais para pagar (equivaleria a se inscrever com o campeonato rolando). */
  championshipStarted: boolean;
  lastPayment?: Payment | null;
}

export interface PayerInput {
  firstName: string;
  lastName: string;
  email: string;
  cpf: string;
  address?: AddressInput;
}

export interface AddressInput {
  zipCode: string;
  street: string;
  number: string;
  neighborhood: string;
  city: string;
  state: string;
}

export interface CardInput {
  token: string;
  paymentMethodId?: string;
  issuerId?: string;
  installments?: number;
  lastFourDigits?: string;
}

export interface CreatePaymentInput {
  method: PaymentMethod;
  payer: PayerInput;
  card?: CardInput;
}

export interface PublicConfig {
  paymentProvider: string;
  mercadoPagoPublicKey?: string | null;
  paymentSimulation: boolean;
  /** Client ID do login com Google; nulo quando desligado. */
  googleClientId?: string | null;
}

export interface ScoreEntry {
  teamId: number;
  teamName: string;
  boxName?: string | null;
  scoreId?: number | null;
  status?: ScoreStatus | null;
  timeMs?: number | null;
  reps?: number | null;
  loadKg?: number | null;
  tiebreakMs?: number | null;
  placement?: number | null;
  points?: number | null;
  display?: string | null;
  notes?: string | null;
  /** Bateria e raia da equipe nesta prova; nulos se as baterias ainda não foram montadas. */
  heatId?: number | null;
  heatNumber?: number | null;
  heatStatus?: HeatStatus | null;
  heatScheduledAt?: string | null;
  lane?: number | null;
  judgeName?: string | null;
}

export interface ScoreInput {
  status: ScoreStatus;
  timeMs?: number | null;
  reps?: number | null;
  loadKg?: number | null;
  tiebreakMs?: number | null;
  notes?: string | null;
  judgeName: string;
}

export interface WorkoutScores {
  workout: Workout;
  categoryName: string;
  entries: ScoreEntry[];
}

export interface GenerateHeatsInput {
  lanes: number;
  firstStart: string;
  intervalMinutes: number;
}

/** Todas as provas do campeonato de uma vez, até 8 equipes por bateria. */
export interface GenerateAllHeatsInput {
  firstStart: string;
  intervalMinutes: number;
}
