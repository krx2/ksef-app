'use client';

import { useState, Suspense } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'next/navigation';
import Link from 'next/link';
import { Plus, Upload, RefreshCw } from 'lucide-react';
import { useUser } from '@/lib/user-context';
import { invoicesApi } from '@/lib/api';
import { formatPLN, formatDate } from '@/lib/utils';
import { StatusBadge, DirectionBadge } from '@/components/ui/StatusBadge';
import type { InvoiceDirection } from '@/types';

function InvoicesList() {
  const { userId } = useUser();
  const searchParams = useSearchParams();
  const [direction, setDirection] = useState<InvoiceDirection | undefined>(
    (searchParams.get('direction') as InvoiceDirection) || undefined
  );
  const [page, setPage] = useState(0);

  const { data, isLoading, refetch, isFetching } = useQuery({
    queryKey: ['invoices', userId, direction, page],
    queryFn: () => invoicesApi.list(userId, { direction, page, size: 20 }),
    enabled: !!userId,
  });

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Faktury</h1>
        <div className="flex items-center gap-2">
          <button
            onClick={() => refetch()}
            className="btn-secondary"
            disabled={isFetching}
          >
            <RefreshCw size={15} className={isFetching ? 'animate-spin' : ''} />
            Odśwież
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

      {/* Direction filter */}
      <div className="flex gap-2 text-sm">
        {([undefined, 'ISSUED', 'RECEIVED'] as const).map(d => (
          <button
            key={String(d)}
            onClick={() => { setDirection(d); setPage(0); }}
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

      {/* Table */}
      <div className="card overflow-hidden">
        {isLoading ? (
          <div className="p-10 text-center text-sm text-gray-400">Ładowanie…</div>
        ) : data?.content.length === 0 ? (
          <div className="p-10 text-center text-sm text-gray-400">Brak faktur do wyświetlenia.</div>
        ) : (
          <>
            <table className="w-full text-sm">
              <thead className="bg-gray-50 text-gray-500 text-xs uppercase tracking-wide border-b border-gray-100">
                <tr>
                  <th className="px-5 py-3 text-left">Numer</th>
                  <th className="px-5 py-3 text-left">Kontrahent</th>
                  <th className="px-5 py-3 text-left">Kierunek</th>
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
