'use client';

import { useState } from 'react';
import { useUser } from '@/lib/user-context';
import { usersApi } from '@/lib/api';

type Mode = 'login' | 'register';

export default function UserSetup() {
  const { setUser } = useUser();
  const [mode, setMode] = useState<Mode>('login');

  // Login state
  const [nip, setNip] = useState('');
  const [pin, setPin] = useState('');
  const [loginLoading, setLoginLoading] = useState(false);
  const [loginError, setLoginError] = useState('');

  // Register state
  const [form, setForm] = useState<{ email: string; nip: string; companyName: string; ksefToken: string; invoicePrefixMode: 'NONE' | 'YEAR_MONTH'; pin: string; pinConfirm: string }>(
    { email: '', nip: '', companyName: '', ksefToken: '', invoicePrefixMode: 'NONE', pin: '', pinConfirm: '' }
  );
  const [registerLoading, setRegisterLoading] = useState(false);
  const [registerError, setRegisterError] = useState('');

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (pin && !/^\d{4,6}$/.test(pin)) {
      setLoginError('Kod PIN musi składać się z 4–6 cyfr.');
      return;
    }
    setLoginLoading(true);
    setLoginError('');
    try {
      const user = await usersApi.login(nip.trim(), pin || undefined);
      setUser(user);
    } catch (err: any) {
      setLoginError(err?.response?.data?.error ?? 'Błąd podczas logowania');
    } finally {
      setLoginLoading(false);
    }
  };

  const handleRegister = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!/^\d{4,6}$/.test(form.pin)) {
      setRegisterError('Kod PIN musi składać się z 4–6 cyfr.');
      return;
    }
    if (form.pin !== form.pinConfirm) {
      setRegisterError('Kody PIN nie są zgodne.');
      return;
    }
    setRegisterLoading(true);
    setRegisterError('');
    try {
      const { pinConfirm: _, ...payload } = form;
      const user = await usersApi.create(payload);
      setUser(user);
    } catch (err: any) {
      setRegisterError(err?.response?.data?.error ?? 'Błąd podczas tworzenia konta');
    } finally {
      setRegisterLoading(false);
    }
  };

  return (
    <div className="max-w-md mx-auto mt-16">
      <div className="card p-8">
        {mode === 'login' ? (
          <>
            <h1 className="text-xl font-semibold mb-1">Logowanie</h1>
            <p className="text-sm text-gray-500 mb-6">Podaj NIP swojej firmy, aby się zalogować.</p>

            <form onSubmit={handleLogin} className="space-y-4">
              <div>
                <label className="label">NIP (10 cyfr)</label>
                <input
                  className="input"
                  required
                  maxLength={10}
                  pattern="\d{10}"
                  placeholder="0000000000"
                  value={nip}
                  onChange={e => setNip(e.target.value)}
                />
              </div>
              <div>
                <label className="label">Kod PIN (4–6 cyfr)</label>
                <input
                  className="input"
                  type="password"
                  inputMode="numeric"
                  maxLength={6}
                  placeholder="Pozostaw puste jeśli nie masz kodu PIN"
                  value={pin}
                  onChange={e => setPin(e.target.value.replace(/\D/g, ''))}
                />
              </div>

              {loginError && <p className="text-sm text-red-600">{loginError}</p>}

              <button type="submit" className="btn-primary w-full justify-center" disabled={loginLoading}>
                {loginLoading ? 'Logowanie…' : 'Zaloguj się'}
              </button>
            </form>

            <p className="text-sm text-center text-gray-500 mt-4">
              Nie masz konta?{' '}
              <button
                className="text-brand-600 hover:underline font-medium"
                onClick={() => { setMode('register'); setLoginError(''); }}
              >
                Zarejestruj się
              </button>
            </p>
          </>
        ) : (
          <>
            <h1 className="text-xl font-semibold mb-1">Rejestracja</h1>
            <p className="text-sm text-gray-500 mb-6">Uzupełnij dane firmy, aby założyć konto.</p>

            <form onSubmit={handleRegister} className="space-y-4">
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
              <div>
                <label className="label">Kod PIN (4–6 cyfr)</label>
                <input className="input" type="password" required inputMode="numeric"
                  maxLength={6} placeholder="••••"
                  value={form.pin}
                  onChange={e => setForm(f => ({ ...f, pin: e.target.value.replace(/\D/g, '') }))} />
              </div>
              <div>
                <label className="label">Powtórz kod PIN</label>
                <input className="input" type="password" required inputMode="numeric"
                  maxLength={6} placeholder="••••"
                  value={form.pinConfirm}
                  onChange={e => setForm(f => ({ ...f, pinConfirm: e.target.value.replace(/\D/g, '') }))} />
              </div>

              <div>
                <label className="label">Format numeru faktury</label>
                <select
                  className="input"
                  value={form.invoicePrefixMode}
                  onChange={e => setForm(f => ({ ...f, invoicePrefixMode: e.target.value as 'NONE' | 'YEAR_MONTH' }))}
                >
                  <option value="NONE">Bez prefiksu (np. 1, 2, 3)</option>
                  <option value="YEAR_MONTH">Rok/Miesiąc (np. 2026/04/1)</option>
                </select>
              </div>

              {registerError && <p className="text-sm text-red-600">{registerError}</p>}

              <button type="submit" className="btn-primary w-full justify-center" disabled={registerLoading}>
                {registerLoading ? 'Zapisywanie…' : 'Utwórz konto'}
              </button>
            </form>

            <p className="text-sm text-center text-gray-500 mt-4">
              Masz już konto?{' '}
              <button
                className="text-brand-600 hover:underline font-medium"
                onClick={() => { setMode('login'); setRegisterError(''); }}
              >
                Zaloguj się
              </button>
            </p>
          </>
        )}
      </div>
    </div>
  );
}
