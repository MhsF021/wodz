import type {
  ChampionshipStatus,
  HeatStatus,
  PaymentMethod,
  PaymentStatus,
  ScoreStatus,
  TeamStatus,
  WorkoutType,
} from '../api/types';

export const UFS = ['AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS', 'MG', 'PA', 'PB', 'PR',
  'PE', 'PI', 'RJ', 'RN', 'RS', 'RO', 'RR', 'SC', 'SP', 'SE', 'TO'];

/** "Ana Paula Souza" -> "Ana" */
export const firstName = (name: string) => name.trim().split(/\s+/)[0];

export const money = (value: number) =>
  Number(value).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });

export const points = (value?: number | null) =>
  value === null || value === undefined ? '' : Number(value).toLocaleString('pt-BR', { maximumFractionDigits: 2 });

/** "2026-11-14" -> Date local (sem deslocamento de fuso). */
export function localDate(iso: string): Date {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

const monthShort = (d: Date) => d.toLocaleDateString('pt-BR', { month: 'short' }).replace('.', '');

export function dateRange(start: string, end: string): string {
  const s = localDate(start);
  const e = localDate(end);
  if (start === end) return `${s.getDate()} ${monthShort(s)} ${s.getFullYear()}`;
  if (s.getMonth() === e.getMonth() && s.getFullYear() === e.getFullYear()) {
    return `${s.getDate()} a ${e.getDate()} ${monthShort(s)} ${s.getFullYear()}`;
  }
  return `${s.getDate()} ${monthShort(s)} a ${e.getDate()} ${monthShort(e)} ${e.getFullYear()}`;
}

export function dayBlock(start: string, end: string) {
  const s = localDate(start);
  const e = localDate(end);
  const days = start === end || s.getMonth() !== e.getMonth() ? `${s.getDate()}` : `${s.getDate()}–${e.getDate()}`;
  return { days, month: monthShort(s) };
}

export const clockTime = (iso: string) =>
  new Date(iso).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });

export const dayLabel = (iso: string) =>
  new Date(iso).toLocaleDateString('pt-BR', { weekday: 'short', day: '2-digit', month: '2-digit' });

export const dateTime = (iso: string) => `${dayLabel(iso)}, ${clockTime(iso)}`;

/** ms -> "07:42" ou "07:42.5" */
export function formatDuration(ms?: number | null): string {
  if (ms === null || ms === undefined) return '';
  const total = Math.floor(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  const rest = ms % 1000;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}${rest >= 100 ? `.${Math.floor(rest / 100)}` : ''}`;
}

/** Relógio de prova: sempre mm:ss (ou h:mm:ss acima de 1h). */
export function formatClock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(s).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

/** Aceita "12:34", "12:34.5", "1:02:03" ou segundos ("754"). Retorna ms ou null. */
export function parseDuration(input: string): number | null {
  const value = input.trim().replace(',', '.');
  if (!value) return null;
  const parts = value.split(':');
  if (parts.length > 3 || parts.some((p) => p === '' || Number.isNaN(Number(p)))) return null;
  let seconds = 0;
  for (const part of parts) seconds = seconds * 60 + Number(part);
  return seconds > 0 ? Math.round(seconds * 1000) : null;
}

export const capLabel = (seconds?: number | null) => (seconds ? formatClock(seconds * 1000) : null);

export const workoutTypeLabel: Record<WorkoutType, string> = {
  FOR_TIME: 'For time',
  AMRAP: 'AMRAP',
  MAX_LOAD: 'Carga máxima (PR)',
  MAX_REPS: 'Máximo de repetições',
};

export const heatStatusLabel: Record<HeatStatus, string> = {
  UPCOMING: 'Próxima',
  IN_PROGRESS: 'Em andamento',
  FINISHED: 'Concluída',
};

export const teamStatusLabel: Record<TeamStatus, string> = {
  PENDING_PAYMENT: 'Aguardando pagamento',
  CONFIRMED: 'Confirmada',
  CANCELLED: 'Cancelada',
};

export const paymentStatusLabel: Record<PaymentStatus, string> = {
  PENDING: 'Aguardando pagamento',
  APPROVED: 'Pago',
  REJECTED: 'Recusado',
  EXPIRED: 'Expirado',
  CANCELLED: 'Cancelado',
  REFUNDED: 'Estornado',
};

export const methodLabel: Record<PaymentMethod, string> = {
  PIX: 'Pix',
  CREDIT_CARD: 'Cartão de crédito',
  DEBIT_CARD: 'Cartão de débito',
  BOLETO: 'Boleto',
};

export const championshipStatusLabel: Record<ChampionshipStatus, string> = {
  DRAFT: 'Rascunho',
  PUBLISHED: 'Publicado',
  IN_PROGRESS: 'Acontecendo agora',
  FINISHED: 'Encerrado',
  CANCELLED: 'Cancelado',
};

export const scoreStatusLabel: Record<ScoreStatus, string> = {
  VALID: 'Válido',
  CAPPED: 'CAP (não terminou)',
  DNS: 'Não compareceu',
  DQ: 'Desclassificada',
};

/** Cores das anilhas olímpicas, usadas para identificar cada categoria. */
const PLATES = ['var(--plate-blue)', 'var(--plate-yellow)', 'var(--plate-green)', 'var(--plate-red)', 'var(--ink)'];
export const plateColor = (index: number) => PLATES[((index % PLATES.length) + PLATES.length) % PLATES.length];

export const onlyDigits = (value: string) => value.replace(/\D/g, '');

export function maskCpf(value: string): string {
  const d = onlyDigits(value).slice(0, 11);
  return d
    .replace(/^(\d{3})(\d)/, '$1.$2')
    .replace(/^(\d{3})\.(\d{3})(\d)/, '$1.$2.$3')
    .replace(/\.(\d{3})(\d{1,2})$/, '.$1-$2');
}

export function isValidCpf(value: string): boolean {
  const d = onlyDigits(value);
  if (d.length !== 11 || /^(\d)\1{10}$/.test(d)) return false;
  const digit = (len: number) => {
    let sum = 0;
    for (let i = 0; i < len; i++) sum += Number(d[i]) * (len + 1 - i);
    const r = (sum * 10) % 11;
    return r === 10 ? 0 : r;
  };
  return digit(9) === Number(d[9]) && digit(10) === Number(d[10]);
}

/** ISO -> valor de <input type="datetime-local"> no fuso do navegador. */
export function toLocalInput(iso?: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
}

export const fromLocalInput = (value: string): string | null => (value ? new Date(value).toISOString() : null);

export const ordinal = (n?: number | null) => (n ? `${n}º` : '');
