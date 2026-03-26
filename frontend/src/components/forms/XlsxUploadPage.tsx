'use client';

import { useState, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { Upload, Eye, Send, FileSpreadsheet } from 'lucide-react';
import { useUser } from '@/lib/user-context';
import { invoicesApi, xlsxConfigsApi } from '@/lib/api';
import Link from 'next/link';

export default function XlsxUploadPage() {
  const router = useRouter();
  const { userId } = useUser();
  const fileRef = useRef<HTMLInputElement>(null);

  const [file, setFile] = useState<File | null>(null);
  const [configId, setConfigId] = useState('');
  const [preview, setPreview] = useState<Record<string, string> | null>(null);
  const [loadingPreview, setLoadingPreview] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const { data: configs } = useQuery({
    queryKey: ['xlsx-configs', userId],
    queryFn: () => xlsxConfigsApi.list(userId),
    enabled: !!userId,
  });

  const handlePreview = async () => {
    if (!file || !configId) return;
    setLoadingPreview(true);
    setError('');
    try {
      const data = await invoicesApi.previewXlsx(userId, file, configId);
      setPreview(data);
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Błąd podglądu');
    } finally {
      setLoadingPreview(false);
    }
  };

  const handleSend = async () => {
    if (!file || !configId) return;
    setLoading(true);
    setError('');
    try {
      await invoicesApi.createFromXlsx(userId, file, configId);
      router.push('/faktury');
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Błąd wysyłania');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="space-y-6 max-w-2xl">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Faktura z pliku XLSX</h1>
        <Link href="/faktury" className="btn-secondary">← Powrót</Link>
      </div>

      {error && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {/* Step 1: select config */}
      <div className="card p-5 space-y-3">
        <h2 className="font-medium text-gray-700">1. Wybierz konfigurację XLSX</h2>
        {configs?.length === 0 ? (
          <p className="text-sm text-gray-500">
            Brak konfiguracji.{' '}
            <Link href="/konfiguracja" className="text-brand-600 underline">
              Utwórz pierwszą konfigurację →
            </Link>
          </p>
        ) : (
          <select className="input" value={configId} onChange={e => setConfigId(e.target.value)}>
            <option value="">— wybierz konfigurację —</option>
            {configs?.map(c => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        )}
      </div>

      {/* Step 2: upload file */}
      <div className="card p-5 space-y-3">
        <h2 className="font-medium text-gray-700">2. Wgraj plik XLSX</h2>
        <div
          className="border-2 border-dashed border-gray-200 rounded-lg p-8 text-center cursor-pointer hover:border-brand-400 transition-colors"
          onClick={() => fileRef.current?.click()}
        >
          <FileSpreadsheet size={32} className="mx-auto text-gray-300 mb-2" />
          {file ? (
            <p className="text-sm font-medium text-gray-700">{file.name}</p>
          ) : (
            <p className="text-sm text-gray-400">Kliknij aby wybrać plik .xlsx</p>
          )}
        </div>
        <input
          ref={fileRef}
          type="file"
          accept=".xlsx,.xls"
          className="hidden"
          onChange={e => { setFile(e.target.files?.[0] ?? null); setPreview(null); }}
        />
      </div>

      {/* Actions */}
      <div className="flex gap-3">
        <button
          className="btn-secondary"
          onClick={handlePreview}
          disabled={!file || !configId || loadingPreview}
        >
          <Eye size={15} />
          {loadingPreview ? 'Wczytywanie…' : 'Podgląd danych'}
        </button>
        <button
          className="btn-primary"
          onClick={handleSend}
          disabled={!file || !configId || loading}
        >
          <Send size={15} />
          {loading ? 'Wysyłanie…' : 'Wyślij do KSeF'}
        </button>
      </div>

      {/* Preview result */}
      {preview && (
        <div className="card overflow-hidden">
          <div className="px-5 py-3 border-b border-gray-100">
            <h3 className="font-medium text-gray-700">Podgląd wczytanych danych</h3>
          </div>
          <table className="w-full text-sm">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-5 py-2 text-left text-gray-500 text-xs font-medium">Pole</th>
                <th className="px-5 py-2 text-left text-gray-500 text-xs font-medium">Wartość</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {Object.entries(preview).map(([key, value]) => (
                <tr key={key}>
                  <td className="px-5 py-2 font-mono text-xs text-gray-500">{key}</td>
                  <td className="px-5 py-2 text-gray-900">{value || <span className="text-gray-300">—</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
