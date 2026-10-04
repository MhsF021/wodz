import { Check } from 'lucide-react';
import type { ChampionshipStatus, HeatStatus, PaymentStatus, TeamStatus } from '../api/types';
import { championshipStatusLabel, heatStatusLabel, paymentStatusLabel, teamStatusLabel } from '../lib/format';

export function HeatBadge({ status }: { status: HeatStatus }) {
  if (status === 'IN_PROGRESS') {
    return (
      <span className="badge badge--live">
        <span className="pulse" aria-hidden />
        {heatStatusLabel[status]}
      </span>
    );
  }
  if (status === 'FINISHED') {
    return (
      <span className="badge badge--done">
        <Check size={14} aria-hidden />
        {heatStatusLabel[status]}
      </span>
    );
  }
  return <span className="badge badge--upcoming">{heatStatusLabel[status]}</span>;
}

const teamTone: Record<TeamStatus, string> = {
  PENDING_PAYMENT: 'badge--pending',
  CONFIRMED: 'badge--done',
  CANCELLED: 'badge--muted',
};

export function TeamBadge({ status }: { status: TeamStatus }) {
  return <span className={`badge ${teamTone[status]}`}>{teamStatusLabel[status]}</span>;
}

const paymentTone: Record<PaymentStatus, string> = {
  PENDING: 'badge--pending',
  APPROVED: 'badge--done',
  REJECTED: 'badge--danger',
  EXPIRED: 'badge--muted',
  CANCELLED: 'badge--muted',
  REFUNDED: 'badge--muted',
};

export function PaymentBadge({ status }: { status: PaymentStatus }) {
  return <span className={`badge ${paymentTone[status]}`}>{paymentStatusLabel[status]}</span>;
}

const championshipTone: Record<ChampionshipStatus, string> = {
  DRAFT: 'badge--muted',
  PUBLISHED: 'badge--upcoming',
  IN_PROGRESS: 'badge--live',
  FINISHED: 'badge--done',
  CANCELLED: 'badge--muted',
};

export function ChampionshipBadge({ status }: { status: ChampionshipStatus }) {
  return <span className={`badge ${championshipTone[status]}`}>{championshipStatusLabel[status]}</span>;
}
