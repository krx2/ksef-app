'use client';

import { useState, useEffect, useRef } from 'react';
import { useRouter } from 'next/navigation';
import { FileDown } from 'lucide-react';
import { useUser } from '@/lib/user-context';
import { reportsApi } from '@/lib/api';
import { formatPLN, formatDate } from '@/lib/utils';
import { MIN_MONTH, getCurrentMonth } from '@/lib/dateUtils';
import { StatusBadge, DirectionBadge } from '@/components/ui/StatusBadge';
import { RODZAJ_FAKTURY_LABELS } from '@/types';
import type { Invoice } from '@/types';

export default function RaportyPage() {
  const { userId, user, isLoaded } = useUser();
  const router = useRouter();

  const [month, setMonth] = useState(getCurrentMonth());
  const [invoices, setInvoices] = useState<Invoice[]>([]);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [isLoadingInvoices, setIsLoadingInvoices] = useState(false);
  const [isGenerating, setIsGenerating] = useState(false);
  const [error, setError] = useState('');
  const checkAllRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (isLoaded && !user) router.replace('/');
  }, [isLoaded, user, router]);

  useEffect(() => {
    if (!userId || !month) return;
    setIsLoadingInvoices(true);
    setError('');
    reportsApi.listForMonth(userId, month)
      .then(list => {
        // Endpoint /api/reports/invoices zwraca listę posortowaną ASC po issueDate
        setInvoices(list);
        setSelectedIds(new Set(list.map(inv => inv.id)));
      })
      .catch(() => setError('Błąd pobierania faktur dla wybranego miesiąca'))
      .finally(() => setIsLoadingInvoices(false));
  }, [userId, month]);

  const allSelected  = invoices.length > 0 && selectedIds.size === invoices.length;
  const someSelected = selectedIds.size > 0 && selectedIds.size < invoices.length;

  useEffect(() => {
    if (checkAllRef.current) {
      checkAllRef.current.indeterminate = someSelected;
    }
  }, [someSelected]);

  const toggleAll = () => {
    if (allSelected) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(invoices.map(inv => inv.id)));
    }
  };

  const toggleOne = (id: string) => {
    setSelectedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const handleGeneratePdf = async () => {
    if (selectedIds.size === 0) return;
    setIsGenerating(true);
    setError('');
    try {
      await reportsApi.generatePdf(userId, month, Array.from(selectedIds));
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Błąd generowania raportu PDF');
    } finally {
      setIsGenerating(false);
    }
  };

  if (!isLoaded || !user) return null;

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">Raporty miesięczne</h1>
          <p className="text-sm text-gray-500 mt-1">
            Wybierz miesiąc, zaznacz faktury i wygeneruj raport PDF.
          </p>
        </div>
        {invoices.length > 0 && (
          <button
            className="btn-primary"
            disabled={selectedIds.size === 0 || isGenerating}
            onClick={handleGeneratePdf}
          >
            <FileDown size={15} />
            {isGenerating ? 'Generowanie PDF…' : `Generuj raport PDF (${selectedIds.size})`}
          </button>
        )}
      </div>

      {/* Month picker */}
      <div className="card p-4 flex items-center gap-4">
        <label className="text-sm font-medium text-gray-700 whitespace-nowrap">Miesiąc:</label>
        <input
          type="month"
          className="input py-1.5 text-sm max-w-48"
          value={month}
          min={MIN_MONTH}
          max={getCurrentMonth()}
          onChange={e => setMonth(e.target.value)}
        />
        {invoices.length > 0 && (
          <span className="text-sm text-gray-500">
            {invoices.length} {invoices.length === 1 ? 'faktura' : invoices.length < 5 ? 'faktury' : 'faktur'} ·{' '}
            <strong>zaznaczono {selectedIds.size}</strong>
          </span>
        )}
      </div>

      {/* Error */}
      {error && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {/* Invoice list */}
      <div className="card overflow-hidden">
        {isLoadingInvoices ? (
          <div className="p-10 text-center text-sm text-gray-400">Ładowanie faktur…</div>
        ) : invoices.length === 0 ? (
          <div className="p-10 text-center text-sm text-gray-400">
            Brak faktur w wybranym miesiącu.
          </div>
        ) : (
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-gray-500 text-xs uppercase tracking-wide border-b border-gray-100">
              <tr>
                <th className="px-4 py-3 text-center w-10">
                  <input
                    ref={checkAllRef}
                    type="checkbox"
                    checked={allSelected}
                    onChange={toggleAll}
                    className="rounded"
                    title={allSelected ? 'Odznacz wszystkie' : 'Zaznacz wszystkie'}
                  />
                </th>
                <th className="px-4 py-3 text-left">Numer</th>
                <th className="px-4 py-3 text-left">Kontrahent</th>
                <th className="px-4 py-3 text-left">Kierunek</th>
                <th className="px-4 py-3 text-left">Rodzaj</th>
                <th className="px-4 py-3 text-left">Data</th>
                <th className="px-4 py-3 text-right">Netto</th>
                <th className="px-4 py-3 text-right">Brutto</th>
                <th className="px-4 py-3 text-left">Status</th>
                <th className="px-4 py-3 text-left">Nr KSeF</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {invoices.map(inv => (
                <tr
                  key={inv.id}
                  className={`hover:bg-gray-50 transition-colors cursor-pointer ${
                    selectedIds.has(inv.id) ? 'bg-brand-50/40' : ''
                  }`}
                  onClick={() => toggleOne(inv.id)}
                >
                  <td className="px-4 py-3 text-center" onClick={e => e.stopPropagation()}>
                    <input
                      type="checkbox"
                      checked={selectedIds.has(inv.id)}
                      onChange={() => toggleOne(inv.id)}
                      className="rounded"
                    />
                  </td>
                  <td className="px-4 py-3 font-mono text-xs">{inv.invoiceNumber}</td>
                  <td className="px-4 py-3">
                    <div className="font-medium text-gray-900">
                      {inv.direction === 'ISSUED' ? inv.buyerName : inv.sellerName}
                    </div>
                    <div className="text-xs text-gray-400">
                      NIP: {inv.direction === 'ISSUED' ? inv.buyerNip : inv.sellerNip}
                    </div>
                  </td>
                  <td className="px-4 py-3"><DirectionBadge direction={inv.direction} /></td>
                  <td className="px-4 py-3 text-xs text-gray-500">
                    {RODZAJ_FAKTURY_LABELS[inv.rodzajFaktury] ?? inv.rodzajFaktury}
                  </td>
                  <td className="px-4 py-3 text-gray-500">{formatDate(inv.issueDate)}</td>
                  <td className="px-4 py-3 text-right">{formatPLN(inv.netAmount)}</td>
                  <td className="px-4 py-3 text-right font-semibold">{formatPLN(inv.grossAmount)}</td>
                  <td className="px-4 py-3"><StatusBadge status={inv.status} /></td>
                  <td className="px-4 py-3 font-mono text-xs text-gray-400">
                    {inv.ksefNumber ?? '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
