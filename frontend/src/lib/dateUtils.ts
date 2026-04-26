/** Najwcześniejszy miesiąc dostępny w filtrach (start KSeF 2.0). */
export const MIN_MONTH = '2026-02';

/** Zwraca bieżący miesiąc w formacie YYYY-MM. */
export function getCurrentMonth(): string {
  return new Date().toISOString().slice(0, 7);
}

/** Przelicza YYYY-MM na zakres dat od pierwszego do ostatniego dnia miesiąca. */
export function monthToDateRange(month: string): { from: string; to: string } {
  const [year, mon] = month.split('-').map(Number);
  const from = `${month}-01`;
  const lastDay = new Date(year, mon, 0).getDate();
  const to = `${month}-${String(lastDay).padStart(2, '0')}`;
  return { from, to };
}
