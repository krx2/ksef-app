'use client';

import { useState, useEffect, Suspense } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams, useRouter } from 'next/navigation';
import Link from 'next/link';
import { Plus, Upload, RefreshCw, Search, X } from 'lucide-react';
import { useUser } from '@/lib/user-context';
import { invoicesApi } from '@/lib/api';
import { formatPLN, formatDate } from '@/lib/utils';
import { StatusBadge, DirectionBadge } from '@/components/ui/StatusBadge';
import type { InvoiceDirection, InvoiceStatus, InvoiceFilters, RodzajFaktury } from '@/types';
import { RODZAJ_FAKTURY_LABELS } from '@/types';

const STATUS_LABELS: Record<InvoiceStatus, string> = {
  DRAFT:               'Szkic',
  QUEUED:              'W kolejce',
  SENDING:             'Wysyłanie',
  SENT:                'Wysłana',
  FAILED:              'Błąd',
  RECEIVED_FROM_KSEF:  'Z KSeF',
};

function InvoicesList() {
  const { userId, user, isLoaded } = useUser();
  const router = useRouter();
  const queryClient = useQueryClient();
  const searchParams = useSearchParams();

  const [fetchState, setFetchState] = useState<'idle' | 'loading' | 'done' | 'error'>('idle');
  const [direction, setDirection] = useState<InvoiceDirection | undefined>(
    (searchParams.get('direction') as InvoiceDirection) || undefined
  );
  const [search, setSearch]               = useState('');
  const [status, setStatus]               = useState<InvoiceStatus | undefined>(undefined);
  const [rodzajFaktury, setRodzajFaktury] = useState<RodzajFaktury | undefined>(undefined);
  const [issueDateFrom, setIssueDateFrom] = useState('');
  const [issueDateTo, setIssueDateTo]     = useState('');
  const [page, setPage]                   = useState(0);

  // Zlicz aktywne filtry dodatkowe (poza kierunkiem)
  const activeFilterCount = [
    search, status, rodzajFaktury, issueDateFrom, issueDateTo,
  ].filter(Boolean).length;

  const filters: InvoiceFilters = {
    direction,
    search:       search.trim() || undefined,
    status,
    rodzajFaktury,
    issueDateFrom: issueDateFrom || undefined,
    issueDateTo:   issueDateTo   || undefined,
    page,
    size: 20,
  };

  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['invoices', userId, filters],
    queryFn:  () => invoicesApi.list(userId, filters),
    enabled:  !!userId,
  });

  useEffect(() => {
    if (isLoaded && !user) router.replace('/');
  }, [isLoaded, user, router]);

  if (!isLoaded || !user) return null;

  const handleRefresh = async () => {
    setFetchState('loading');
    try {
      if (user?.hasKsefToken) {
        await invoicesApi.fetchFromKsef(userId);
      }
      // Poczekaj chwilę na przetworzenie przez RabbitMQ, potem odśwież listę
      setTimeout(() => {
        queryClient.invalidateQueries({ queryKey: ['invoices', userId] });
        setFetchState('done');
        setTimeout(() => setFetchState('idle'), 3000);
      }, 3000);
    } catch {
      refetch();
      setFetchState('error');
      setTimeout(() => setFetchState('idle'), 3000);
    }
  };

  const resetFilters = () => {
    setSearch('');
    setStatus(undefined);
    setRodzajFaktury(undefined);
    setIssueDateFrom('');
    setIssueDateTo('');
    setPage(0);
  };

  const handleDirectionChange = (d: InvoiceDirection | undefined) => {
    setDirection(d);
    setPage(0);
  };

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Faktury</h1>
        <div className="flex items-center gap-2">
          <button
            onClick={handleRefresh}
            className="btn-secondary"
            disabled={fetchState === 'loading' || isFetching}
            title={user?.hasKsefToken ? 'Pobierz nowe faktury z KSeF i odśwież listę' : 'Odśwież listę (brak tokenu KSeF)'}
          >
            <RefreshCw size={15} className={fetchState === 'loading' || isFetching ? 'animate-spin' : ''} />
            {fetchState === 'loading' ? 'Sprawdzanie KSeF…' : fetchState === 'done' ? 'Odświeżono ✓' : fetchState === 'error' ? 'Odświeżono' : 'Odśwież'}
          </button>
          <Link href="/faktury/nowa?source=xlsx" className="btn-secondary">
            <Upload size={15} />
            Z pliku XLSX
          </Link>
          <Link href="/faktury/nowa" className="btn-primary">
            <Plus size={15} />
            Nowa faktura
          </Link>
        </div>
      </div>

      {/* Filters */}
      <div className="card p-4 space-y-3">
        {/* Row 1: direction + search */}
        <div className="flex flex-wrap items-center gap-3">
          {/* Direction toggle */}
          <div className="flex gap-1 text-sm">
            {([undefined, 'ISSUED', 'RECEIVED'] as const).map(d => (
              <button
                key={String(d)}
                onClick={() => handleDirectionChange(d)}
                className={`px-3 py-1.5 rounded-full border transition-colors ${
                  direction === d
                    ? 'border-brand-500 bg-brand-50 text-brand-700 font-medium'
                    : 'border-gray-200 text-gray-600 hover:bg-gray-50'
                }`}
              >
                {d === undefined ? 'Wszystkie' : d === 'ISSUED' ? 'Wystawione' : 'Odebrane'}
              </button>
            ))}
          </div>

          {/* Search */}
          <div className="relative flex-1 min-w-48">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
            <input
              type="search"
              placeholder="Szukaj wg numeru, nazwy lub NIP kontrahenta…"
              className="input pl-8 py-1.5 text-sm"
              value={search}
              onChange={e => { setSearch(e.target.value); setPage(0); }}
            />
          </div>

          {/* Reset button */}
          {activeFilterCount > 0 && (
            <button onClick={resetFilters} className="btn-secondary py-1.5 text-xs flex items-center gap-1">
              <X size={12} /> Wyczyść filtry ({activeFilterCount})
            </button>
          )}
        </div>

        {/* Row 2: status, rodzajFaktury, date range */}
        <div className="flex flex-wrap items-center gap-3 text-sm">
          {/* Status */}
          <div className="flex items-center gap-1.5">
            <label className="text-gray-500 whitespace-nowrap">Status:</label>
            <select
              className="input py-1 text-sm min-w-32"
              value={status ?? ''}
              onChange={e => { setStatus((e.target.value as InvoiceStatus) || undefined); setPage(0); }}
            >
              <option value="">Wszystkie</option>
              {(Object.keys(STATUS_LABELS) as InvoiceStatus[]).map(s => (
                <option key={s} value={s}>{STATUS_LABELS[s]}</option>
              ))}
            </select>
          </div>

          {/* Rodzaj faktury */}
          <div className="flex items-center gap-1.5">
            <label className="text-gray-500 whitespace-nowrap">Rodzaj:</label>
            <select
              className="input py-1 text-sm min-w-44"
              value={rodzajFaktury ?? ''}
              onChange={e => { setRodzajFaktury((e.target.value as RodzajFaktury) || undefined); setPage(0); }}
            >
              <option value="">Wszystkie</option>
              {(Object.keys(RODZAJ_FAKTURY_LABELS) as RodzajFaktury[]).map(k => (
                <option key={k} value={k}>{RODZAJ_FAKTURY_LABELS[k]}</option>
              ))}
            </select>
          </div>

          {/* Data wystawienia od / do */}
          <div className="flex items-center gap-1.5">
            <label className="text-gray-500 whitespace-nowrap">Data od:</label>
            <input
              type="date"
              className="input py-1 text-sm"
              value={issueDateFrom}
              onChange={e => { setIssueDateFrom(e.target.value); setPage(0); }}
            />
          </div>
          <div className="flex items-center gap-1.5">
            <label className="text-gray-500 whitespace-nowrap">do:</label>
            <input
              type="date"
              className="input py-1 text-sm"
              value={issueDateTo}
              onChange={e => { setIssueDateTo(e.target.value); setPage(0); }}
            />
          </div>
        </div>
      </div>

      {/* Table */}
      <div className="card overflow-hidden">
        {isLoading ? (
          <div className="p-10 text-center text-sm text-gray-400">Ładowanie…</div>
        ) : data?.content.length === 0 ? (
          <div className="p-10 text-center text-sm text-gray-400">
            {activeFilterCount > 0 || search
              ? 'Brak faktur spełniających kryteria filtrowania.'
              : 'Brak faktur do wyświetlenia.'}
          </div>
        ) : (
          <>
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-gray-500 text-xs uppercase tracking-wide border-b border-gray-100">
                <tr>
                  <th className="px-5 py-3 text-left">Numer</th>
                  <th className="px-5 py-3 text-left">Kontrahent</th>
                  <th className="px-5 py-3 text-left">Kierunek</th>
                  <th className="px-5 py-3 text-left">Rodzaj</th>
                  <th className="px-5 py-3 text-left">Data</th>
                  <th className="px-5 py-3 text-right">Netto</th>
                  <th className="px-5 py-3 text-right">Brutto</th>
                  <th className="px-5 py-3 text-left">Status</th>
                  <th className="px-5 py-3 text-left">Nr KSeF</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {data?.content.map(inv => (
                  <tr key={inv.id} className="hover:bg-gray-50 transition-colors">
                    <td className="px-5 py-3 font-mono text-xs">{inv.invoiceNumber}</td>
                    <td className="px-5 py-3">
                      <div className="font-medium text-gray-900">
                        {inv.direction === 'ISSUED' ? inv.buyerName : inv.sellerName}
                      </div>
                      <div className="text-xs text-gray-400">
                        NIP: {inv.direction === 'ISSUED' ? inv.buyerNip : inv.sellerNip}
                      </div>
                    </td>
                    <td className="px-5 py-3"><DirectionBadge direction={inv.direction} /></td>
                    <td className="px-5 py-3 text-xs text-gray-500">
                      {RODZAJ_FAKTURY_LABELS[inv.rodzajFaktury] ?? inv.rodzajFaktury}
                    </td>
                    <td className="px-5 py-3 text-gray-500">{formatDate(inv.issueDate)}</td>
                    <td className="px-5 py-3 text-right">{formatPLN(inv.netAmount)}</td>
                    <td className="px-5 py-3 text-right font-semibold">{formatPLN(inv.grossAmount)}</td>
                    <td className="px-5 py-3"><StatusBadge status={inv.status} /></td>
                    <td className="px-5 py-3 font-mono text-xs text-gray-400">
                      {inv.ksefNumber ?? '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>

            {/* Pagination */}
            {data && data.totalPages > 1 && (
              <div className="flex items-center justify-between px-5 py-3 border-t border-gray-100 text-sm">
                <span className="text-gray-500">
                  {data.totalElements} faktur · strona {data.page + 1} z {data.totalPages}
                </span>
                <div className="flex gap-2">
                  <button
                    className="btn-secondary py-1"
                    disabled={page === 0}
                    onClick={() => setPage(p => p - 1)}
                  >
                    ← Poprzednia
                  </button>
                  <button
                    className="btn-secondary py-1"
                    disabled={page >= data.totalPages - 1}
                    onClick={() => setPage(p => p + 1)}
                  >
                    Następna →
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

export default function FakturyPage() {
  return (
    <Suspense>
      <InvoicesList />
    </Suspense>
  );
}
