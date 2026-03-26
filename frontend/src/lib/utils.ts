import { clsx, type ClassValue } from 'clsx';
import type { InvoiceStatus, InvoiceDirection } from '@/types';

export function cn(...inputs: ClassValue[]) {
  return clsx(inputs);
}

export function formatPLN(amount: number): string {
  return new Intl.NumberFormat('pl-PL', {
    style: 'currency',
    currency: 'PLN',
    minimumFractionDigits: 2,
  }).format(amount);
}

export function formatDate(dateStr: string): string {
  if (!dateStr) return '—';
  return new Intl.DateTimeFormat('pl-PL').format(new Date(dateStr));
}

export const STATUS_LABELS: Record<InvoiceStatus, string> = {
  DRAFT:               'Szkic',
  QUEUED:              'W kolejce',
  SENDING:             'Wysyłanie',
  SENT:                'Wysłana',
  FAILED:              'Błąd',
  RECEIVED_FROM_KSEF:  'Odebrana z KSeF',
};

export const STATUS_COLORS: Record<InvoiceStatus, string> = {
  DRAFT:               'bg-gray-100 text-gray-700',
  QUEUED:              'bg-yellow-100 text-yellow-800',
  SENDING:             'bg-blue-100 text-blue-800',
  SENT:                'bg-green-100 text-green-800',
  FAILED:              'bg-red-100 text-red-800',
  RECEIVED_FROM_KSEF:  'bg-purple-100 text-purple-800',
};

export const DIRECTION_LABELS: Record<InvoiceDirection, string> = {
  ISSUED:   'Wystawiona',
  RECEIVED: 'Odebrana',
};
