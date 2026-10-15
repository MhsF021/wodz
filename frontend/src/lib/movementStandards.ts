import type { CategoryGender, MovementStandard } from '../api/types';

export function isAvailabilitySymbol(value: string) {
  return /^[✓✔✕✗×]$/.test(value.trim());
}

export function normalizeMovementStandard(row: MovementStandard, gender?: CategoryGender | null): MovementStandard {
  const hasLoad = (value: string) => Boolean(value.trim() && value.trim() !== '—' && value.trim() !== '-' && !isAvailabilitySymbol(value));
  const unavailable = (value: string) => /^[✕✗×]$/.test(value.trim());
  const selected = gender === 'MALE' ? !unavailable(row.male) : gender === 'FEMALE' ? !unavailable(row.female)
    : ![row.male, row.female].every(unavailable);
  return {
    ...row,
    kind: row.kind ?? (hasLoad(row.male) || hasLoad(row.female) ? 'LOAD' : 'MOVEMENT'),
    allowed: row.allowed !== false && selected,
    male: isAvailabilitySymbol(row.male) ? '' : row.male,
    female: isAvailabilitySymbol(row.female) ? '' : row.female,
  };
}
