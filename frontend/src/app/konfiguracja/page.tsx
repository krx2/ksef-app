'use client';

import { useState, useEffect } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { Plus, Pencil, Trash2, Key, Hash, Mail, Download, Lock } from 'lucide-react';
import { useUser } from '@/lib/user-context';
import { xlsxConfigsApi, usersApi, userPrefixApi, notificationEmailsApi, invoicesApi } from '@/lib/api';
import { KSEF_HISTORY_START } from '@/lib/api';
import XlsxConfigModal from '@/components/forms/XlsxConfigModal';
import type { XlsxConfig } from '@/types';
import type { NotificationEmail } from '@/types';

export default function KonfiguracjaPage() {
  const { userId, user, setUser, isLoaded } = useUser();
  const router = useRouter();
  const qc = useQueryClient();

  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<XlsxConfig | null>(null);
  const [ksefToken, setKsefToken] = useState('');
  const [savingToken, setSavingToken] = useState(false);
  const [historyState, setHistoryState] = useState<'idle' | 'loading' | 'done' | 'error'>('idle');

  const [newPin, setNewPin] = useState('');
  const [newPinConfirm, setNewPinConfirm] = useState('');
  const [savingPin, setSavingPin] = useState(false);
  const [pinError, setPinError] = useState('');
  const [pinSuccess, setPinSuccess] = useState(false);

  const { data: notificationEmails, refetch: refetchEmails } = useQuery<NotificationEmail[]>({
    queryKey: ['notification-emails', userId],
    queryFn:  () => notificationEmailsApi.list(userId),
    enabled:  !!userId,
  });
  const [newEmail, setNewEmail]       = useState('');
  const [newLabel, setNewLabel]       = useState('');
  const [addingEmail, setAddingEmail] = useState(false);
  const [emailError, setEmailError]   = useState('');

  const handleAddEmail = async () => {
    if (!newEmail.trim()) return;
    setAddingEmail(true); setEmailError('');
    try {
      await notificationEmailsApi.add(userId, newEmail.trim(), newLabel.trim() || undefined);
      setNewEmail(''); setNewLabel('');
      refetchEmails();
    } catch (err: unknown) {
      const axiosErr = err as { response?: { data?: { error?: string } } };
      setEmailError(axiosErr?.response?.data?.error ?? 'Błąd dodawania adresu');
    } finally { setAddingEmail(false); }
  };

  const handleRemoveEmail = async (id: string) => {
    if (!confirm('Na pewno usunąć ten adres z listy powiadomień?')) return;
    await notificationEmailsApi.remove(userId, id);
    refetchEmails();
  };

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

  const handleUpdatePrefixMode = async (mode: 'NONE' | 'YEAR_MONTH') => {
    if (!userId || !user) return;
    await userPrefixApi.updatePrefixMode(userId, mode);
    setUser({ ...user, invoicePrefixMode: mode });
  };

  const handleFetchHistory = async () => {
    if (!userId || !user?.hasKsefToken) return;
    setHistoryState('loading');
    try {
      await invoicesApi.fetchHistoryFromKsef(userId);
      setHistoryState('done');
      setTimeout(() => setHistoryState('idle'), 5000);
    } catch {
      setHistoryState('error');
      setTimeout(() => setHistoryState('idle'), 5000);
    }
  };

  const handleSavePin = async () => {
    if (!/^\d{4,6}$/.test(newPin)) {
      setPinError('PIN musi składać się z 4–6 cyfr.');
      return;
    }
    if (newPin !== newPinConfirm) {
      setPinError('Kody PIN nie są zgodne.');
      return;
    }
    setSavingPin(true);
    setPinError('');
    setPinSuccess(false);
    try {
      await usersApi.setPin(userId, newPin);
      if (user) setUser({ ...user, hasPin: true });
      setNewPin('');
      setNewPinConfirm('');
      setPinSuccess(true);
      setTimeout(() => setPinSuccess(false), 4000);
    } catch (err: unknown) {
      const axiosErr = err as { response?: { data?: { error?: string } } };
      setPinError(axiosErr?.response?.data?.error ?? 'Błąd podczas zapisywania PIN-u');
    } finally {
      setSavingPin(false);
    }
  };

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

      {/* PIN section */}
      <div className="card p-5 space-y-3">
        <div className="flex items-center gap-2">
          <Lock size={16} className="text-gray-500" />
          <h2 className="font-medium text-gray-700">Kod PIN</h2>
          {user?.hasPin ? (
            <span className="badge bg-green-100 text-green-700">Ustawiony</span>
          ) : (
            <span className="badge bg-amber-100 text-amber-700">Nie ustawiony</span>
          )}
        </div>
        <p className="text-sm text-gray-500">
          {user?.hasPin
            ? 'Zmień kod PIN używany przy logowaniu.'
            : 'Ustaw kod PIN (4–6 cyfr), aby zabezpieczyć dostęp do konta.'}
        </p>
        <div className="flex gap-2 flex-wrap items-end">
          <div>
            <label className="label text-xs">Nowy PIN</label>
            <input
              className="input max-w-40"
              type="password"
              inputMode="numeric"
              maxLength={6}
              placeholder="••••"
              value={newPin}
              onChange={e => { setNewPin(e.target.value.replace(/\D/g, '')); setPinError(''); }}
            />
          </div>
          <div>
            <label className="label text-xs">Powtórz PIN</label>
            <input
              className="input max-w-40"
              type="password"
              inputMode="numeric"
              maxLength={6}
              placeholder="••••"
              value={newPinConfirm}
              onChange={e => { setNewPinConfirm(e.target.value.replace(/\D/g, '')); setPinError(''); }}
            />
          </div>
          <button
            className="btn-primary"
            onClick={handleSavePin}
            disabled={!newPin || !newPinConfirm || savingPin}
          >
            {savingPin ? 'Zapisywanie…' : user?.hasPin ? 'Zmień PIN' : 'Ustaw PIN'}
          </button>
        </div>
        {pinError && <p className="text-sm text-red-600">{pinError}</p>}
        {pinSuccess && <p className="text-sm text-green-700 font-medium">PIN zapisany pomyślnie.</p>}
      </div>

      {/* Import historyczny KSeF */}
      <div className="card p-5 space-y-3">
        <div className="flex items-center gap-2">
          <Download size={16} className="text-gray-500" />
          <h2 className="font-medium text-gray-700">Import historyczny z KSeF</h2>
        </div>
        <p className="text-sm text-gray-500">
          Pobiera wszystkie faktury z KSeF od {KSEF_HISTORY_START} do dziś i zapisuje je w bazie danych.
          Duplikaty są automatycznie pomijane. Import działa w tle — faktury pojawią się na liście po chwili.
        </p>
        {historyState === 'done' && (
          <p className="text-sm text-green-700 font-medium">
            Import zlecony — faktury będą dostępne na liście za chwilę.
          </p>
        )}
        {historyState === 'error' && (
          <p className="text-sm text-red-600">Błąd zlecenia importu. Spróbuj ponownie.</p>
        )}
        <button
          className="btn-secondary"
          onClick={handleFetchHistory}
          disabled={!user?.hasKsefToken || historyState === 'loading' || historyState === 'done'}
          title={!user?.hasKsefToken ? 'Wymagany token KSeF' : 'Pobierz faktury historyczne z KSeF'}
        >
          <Download size={15} className={historyState === 'loading' ? 'animate-bounce' : ''} />
          {historyState === 'loading' ? 'Zlecanie importu…' : historyState === 'done' ? 'Import zlecony ✓' : 'Pobierz stare faktury'}
        </button>
        {!user?.hasKsefToken && (
          <p className="text-xs text-amber-600">Wymagany token KSeF — skonfiguruj go powyżej.</p>
        )}
      </div>

      <div className="card p-5 space-y-4">
        <div className="flex items-center gap-2">
          <Mail size={16} className="text-gray-500" />
          <h2 className="font-medium text-gray-700">Adresy email do powiadomień</h2>
        </div>
        <p className="text-sm text-gray-500">
          Powiadomienia o nowych i wysłanych fakturach będą trafiać na poniższe adresy.
          {(!notificationEmails || notificationEmails.length === 0) && (
            <span className="ml-1 text-amber-600 font-medium">
              Brak adresów — powiadomienia wysyłane na główny email konta ({user.email}).
            </span>
          )}
        </p>

        {notificationEmails && notificationEmails.length > 0 && (
          <div className="divide-y divide-gray-100 border border-gray-200 rounded-lg overflow-hidden">
            {notificationEmails.map(entry => (
              <div key={entry.id} className="flex items-center justify-between px-4 py-3">
                <div>
                  <span className="font-medium text-gray-900">{entry.email}</span>
                  {entry.label && (
                    <span className="ml-2 text-xs text-gray-400 bg-gray-100 rounded px-1.5 py-0.5">
                      {entry.label}
                    </span>
                  )}
                </div>
                <button
                  className="btn-danger py-1 px-2 text-xs"
                  onClick={() => handleRemoveEmail(entry.id)}
                  title="Usuń adres z listy"
                >
                  <Trash2 size={13} />
                </button>
              </div>
            ))}
          </div>
        )}

        {emailError && <p className="text-sm text-red-600">{emailError}</p>}
        <div className="flex gap-2 flex-wrap">
          <input
            className="input max-w-xs text-sm"
            type="email"
            placeholder="adres@email.pl"
            value={newEmail}
            onChange={e => setNewEmail(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && handleAddEmail()}
          />
          <input
            className="input max-w-44 text-sm"
            type="text"
            placeholder="Etykieta (opcjonalna)"
            value={newLabel}
            onChange={e => setNewLabel(e.target.value)}
          />
          <button
            className="btn-primary"
            onClick={handleAddEmail}
            disabled={!newEmail.trim() || addingEmail}
          >
            <Plus size={15} />
            {addingEmail ? 'Dodawanie…' : 'Dodaj adres'}
          </button>
        </div>
      </div>

      <div className="card p-5 space-y-3">
        <div className="flex items-center gap-2">
          <Hash size={16} className="text-gray-500" />
          <h2 className="font-medium text-gray-700">Format numeru faktury</h2>
        </div>
        <p className="text-sm text-gray-500">
          Opcjonalny prefiks roku i miesiąca dodawany automatycznie do podanego numeru.
        </p>
        <select
          className="input max-w-xs"
          value={user?.invoicePrefixMode ?? 'NONE'}
          onChange={e => handleUpdatePrefixMode(e.target.value as 'NONE' | 'YEAR_MONTH')}
        >
          <option value="NONE">Bez prefiksu (np. 1, 2, 3)</option>
          <option value="YEAR_MONTH">Rok/Miesiąc (np. 2026/04/1)</option>
        </select>
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
