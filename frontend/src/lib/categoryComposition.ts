import type { Category, Gender } from '../api/types';

export function categoryAllowsGender(category: Category, gender: Gender): boolean {
  return category.genderPolicy === 'MIXED'
    || category.genderPolicy === 'MALE' && gender === 'M'
    || category.genderPolicy === 'FEMALE' && gender === 'F';
}

export function compositionLabel(men: number, size: number): string {
  const women = size - men;
  return `${men} ${men === 1 ? 'homem' : 'homens'} e ${women} ${women === 1 ? 'mulher' : 'mulheres'}`;
}

export function categoryComposition(category: Category): string {
  if (category.genderPolicy === 'MALE') return 'Masculina';
  if (category.genderPolicy === 'FEMALE') return 'Feminina';
  if (category.genderPolicy === 'MIXED') return `Mista: ${(category.allowedMaleCounts ?? []).map(n => compositionLabel(n, category.teamSize)).join(' ou ')}`;
  return 'Composição ainda não definida pela organização';
}

export function matchesComposition(category: Category, genders: (Gender | '')[]): boolean {
  if (genders.length !== category.teamSize || genders.some(g => g !== 'M' && g !== 'F')) return false;
  const men = genders.filter(g => g === 'M').length;
  if (category.genderPolicy === 'MALE') return men === genders.length;
  if (category.genderPolicy === 'FEMALE') return men === 0;
  return category.genderPolicy === 'MIXED' && (category.allowedMaleCounts ?? []).includes(men);
}
