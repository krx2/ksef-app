'use client';

import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { Plus, Pencil, Trash2, Key } from 'lucide-react';
import { useUser } from '@/lib/user-context';
import { xlsxConfigsApi, usersApi } from '@/lib/api';
import XlsxConfigModal from '@/components/forms/XlsxConfigModal';
import type { XlsxConfig } from '@/types';

export default function KonfiguracjaPage() {
  const { userId, user, setUser, isLoaded } = useUser();
  const router = useRouter();
  const qc = useQueryClient();

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<XlsxConfig | null>(null);
  const [ksefToken, setKsefToken] = useState('');
  const [savingToken, setSavingToken] = useState(false);

  const { data: configs, isLoading } = useQuery({
    queryKey: ['xlsx-configs', userId],
    queryFn: () => xlsxConfigsApi.list(userId),
    enabled: !!userId,
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => xlsxConfigsApi.delete(userId, id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['xlsx-configs', userId] }),
  });

  useEffect(() => {
    if (isLoaded && !user) router.replace('/');
  }, [isLoaded, user, router]);

  if (!isLoaded || !user) return null;

  const handleSaveToken = async () => {
    if (!userId || !ksefToken) return;
    setSavingToken(true);
    try {
      await usersApi.updateToken(userId, ksefToken);
      if (user) setUser({ ...user, hasKsefToken: true });
      setKsefToken('');
      alert('Token KSeF zapisany.');
    } finally {
      setSavingToken(false);
    }
  };

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-semibold">Konfiguracja</h1>

      {/* KSeF token section */}
      <div className="card p-5 space-y-3">
        <div className="flex items-center gap-2">
          <Key size={16} className="text-gray-500" />
          <h2 className="font-medium text-gray-700">Token KSeF</h2>
          {user?.hasKsefToken && (
            <span className="badge bg-green-100 text-green-700">Skonfigurowany</span>
          )}
        </div>
        <p className="text-sm text-gray-500">
          Token używany do autoryzacji sesji w środowisku testowym KSeF (authByToken).
        </p>
        <div className="flex gap-2">
          <input
            className="input max-w-sm"
            type="password"
            placeholder="Wklej nowy token KSeF…"
            value={ksefToken}
            onChange={e => setKsefToken(e.target.value)}
          />
          <button
            className="btn-primary"
            onClick={handleSaveToken}
            disabled={!ksefToken || savingToken}
          >
            {savingToken ? 'Zapisywanie…' : 'Zapisz token'}
          </button>
        </div>
      </div>

      {/* XLSX configs section */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="font-medium text-gray-900">Konfiguracje XLSX</h2>
          <button
            className="btn-primary"
            onClick={() => { setEditing(null); setModalOpen(true); }}
          >
            <Plus size={15} />
            Nowa konfiguracja
          </button>
        </div>

        {isLoading ? (
          <p className="text-sm text-gray-400">Ładowanie…</p>
        ) : configs?.length === 0 ? (
          <div className="card p-10 text-center text-sm text-gray-400">
            <p>Brak konfiguracji XLSX.</p>
            <p className="mt-1">Utwórz konfigurację, aby móc wczytywać faktury z pliku Excel.</p>
          </div>
        ) : (
          <div className="space-y-2">
            {configs?.map(config => (
              <div key={config.id} className="card p-4 flex items-center justify-between">
                <div>
                  <p className="font-medium text-gray-900">{config.name}</p>
                  {config.description && (
                    <p className="text-sm text-gray-500">{config.description}</p>
                  )}
                  <p className="text-xs text-gray-400 mt-0.5">
                    {Object.keys(config.fieldMappings).length} pól · dodano{' '}
                    {new Intl.DateTimeFormat('pl-PL').format(new Date(config.createdAt))}
                  </p>
                </div>
                <div className="flex gap-2">
                  <button
                    className="btn-secondary py-1"
                    onClick={() => { setEditing(config); setModalOpen(true); }}
                  >
                    <Pencil size={14} />
                    Edytuj
                  </button>
                  <button
                    className="btn-danger py-1"
                    onClick={() => {
                      if (confirm('Na pewno usunąć tę konfigurację?'))
                        deleteMutation.mutate(config.id);
                    }}
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Modal */}
      {modalOpen && (
        <XlsxConfigModal
          config={editing}
          onClose={() => setModalOpen(false)}
          onSaved={() => {
            setModalOpen(false);
            qc.invalidateQueries({ queryKey: ['xlsx-configs', userId] });
          }}
        />
      )}
    </div>
  );
}
