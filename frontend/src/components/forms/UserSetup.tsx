'use client';

import { useState } from 'react';
import { useUser } from '@/lib/user-context';
import { usersApi } from '@/lib/api';

export default function UserSetup() {
  const { setUser } = useUser();
  const [form, setForm] = useState({ email: '', nip: '', companyName: '', ksefToken: '' });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handle = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');
    try {
      const user = await usersApi.create(form);
      setUser(user);
    } catch (err: any) {
      setError(err?.response?.data?.error ?? 'Błąd podczas tworzenia konta');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="max-w-md mx-auto mt-16">
      <div className="card p-8">
        <h1 className="text-xl font-semibold mb-1">Witaj w KSeF Faktury</h1>
        <p className="text-sm text-gray-500 mb-6">Uzupełnij dane firmy, aby zacząć.</p>

        <form onSubmit={handle} className="space-y-4">
          <div>
            <label className="label">Email</label>
            <input className="input" type="email" required
              value={form.email} onChange={e => setForm(f => ({ ...f, email: e.target.value }))} />
          </div>
          <div>
            <label className="label">NIP (10 cyfr)</label>
            <input className="input" required maxLength={10} pattern="\d{10}"
              value={form.nip} onChange={e => setForm(f => ({ ...f, nip: e.target.value }))} />
          </div>
          <div>
            <label className="label">Nazwa firmy</label>
            <input className="input" required
              value={form.companyName} onChange={e => setForm(f => ({ ...f, companyName: e.target.value }))} />
          </div>
          <div>
            <label className="label">Token KSeF (opcjonalnie)</label>
            <input className="input" type="password"
              placeholder="Możesz dodać później w Konfiguracji"
              value={form.ksefToken} onChange={e => setForm(f => ({ ...f, ksefToken: e.target.value }))} />
          </div>

          {error && <p className="text-sm text-red-600">{error}</p>}

          <button type="submit" className="btn-primary w-full justify-center" disabled={loading}>
            {loading ? 'Zapisywanie…' : 'Utwórz konto'}
          </button>
        </form>
      </div>
    </div>
  );
}
