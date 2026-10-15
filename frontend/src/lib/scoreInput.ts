import type { ScoreInput, ScoreStatus, Workout } from '../api/types';
import { parseDuration } from './format';

export interface ScoreForm {
  judge: string; status: ScoreStatus; time: string; reps: string; rounds: string; load: string; tiebreak: string;
}

export function buildScoreInput(workout: Workout, form: ScoreForm): { input: ScoreInput; error?: never } | { error: string; input?: never } {
  const fail = (error: string) => ({ error });
  if (!form.judge.trim()) return fail('Informe o nome do judge da raia');
  const input: ScoreInput = { status: form.status, judgeName: form.judge.trim() };
  if (form.status === 'DNS' || form.status === 'DQ') return { input };
  if (form.status === 'CAPPED' && workout.type !== 'FOR_TIME') return fail('CAP só se aplica a provas For time');
  const num = (value: string) => value.trim() === '' ? null : Number(value.replace(',', '.'));

  if (workout.type === 'FOR_TIME' && form.status === 'VALID') {
    const ms = parseDuration(form.time);
    if (!ms || ms > 2_147_483_647) return fail('Tempo inválido. Use mm:ss, ex.: 07:42');
    if (workout.timeCapSeconds != null && ms > workout.timeCapSeconds * 1000) return fail('Tempo acima do time cap. Selecione CAP e informe as repetições realizadas.');
    input.timeMs = ms;
  } else if (workout.type === 'MAX_LOAD') {
    const kg = num(form.load);
    if (kg === null || !Number.isFinite(kg) || kg < 0 || kg > 99999.99) return fail('Informe a carga total em kg, entre 0 e 99999,99');
    input.loadKg = kg;
  } else {
    if (workout.type === 'FOR_TIME' && (!workout.timeCapSeconds || workout.maxReps == null)) return fail('Configure o time cap e o total de repetições em Editar prova antes de lançar no CAP.');
    const rpr = workout.type === 'AMRAP' ? workout.repsPerRound ?? 0 : 0;
    if (workout.type === 'AMRAP' && !rpr) return fail('Informe as repetições por round em Editar prova antes de lançar o AMRAP.');
    const reps = num(form.reps) ?? 0;
    const rounds = rpr ? num(form.rounds) ?? 0 : 0;
    if (!Number.isInteger(reps) || !Number.isInteger(rounds) || reps < 0 || rounds < 0 || (form.reps.trim() === '' && (!rpr || form.rounds.trim() === ''))) return fail('Informe rounds e repetições com números inteiros a partir de zero.');
    if (rpr && reps >= rpr) return fail(`As reps extras devem ficar entre 0 e ${rpr - 1}. A cada ${rpr} reps, acrescente um round completo.`);
    const total = rounds * rpr + reps;
    if (workout.maxReps != null && total > workout.maxReps) return fail(`Esta prova permite no máximo ${workout.maxReps} repetições. Revise o resultado.`);
    if (!Number.isSafeInteger(total) || total > 2_147_483_647) return fail('Quantidade de repetições inválida.');
    input.reps = total;
  }
  if (workout.hasTiebreak && workout.type !== 'MAX_LOAD' && form.tiebreak.trim()) {
    const ms = parseDuration(form.tiebreak);
    if (!ms || ms > 2_147_483_647) return fail('Tempo de desempate inválido');
    const limit = input.timeMs ?? (workout.timeCapSeconds != null ? workout.timeCapSeconds * 1000 : Infinity);
    if (ms > limit) return fail('O desempate não pode ultrapassar o tempo da prova.');
    input.tiebreakMs = ms;
  }
  return { input };
}
