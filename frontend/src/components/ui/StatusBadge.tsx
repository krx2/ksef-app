import { cn, STATUS_COLORS, STATUS_LABELS, DIRECTION_LABELS } from '@/lib/utils';
import type { InvoiceStatus, InvoiceDirection } from '@/types';

export function StatusBadge({ status }: { status: InvoiceStatus }) {
  return (
    <span className={cn('badge', STATUS_COLORS[status])}>
      {STATUS_LABELS[status]}
    </span>
  );
}

export function DirectionBadge({ direction }: { direction: InvoiceDirection }) {
  const colors = direction === 'ISSUED'
    ? 'bg-blue-50 text-blue-700'
    : 'bg-indigo-50 text-indigo-700';
  return (
    <span className={cn('badge', colors)}>
      {DIRECTION_LABELS[direction]}
    </span>
  );
}
