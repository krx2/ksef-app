'use client';

import { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import type { AppUser } from '@/types';
import { invoicesApi } from '@/lib/api';

interface UserContextValue {
  user: AppUser | null;
  setUser: (u: AppUser | null) => void;
  userId: string;
  logout: () => void;
  isLoaded: boolean;
  newReceivedCount: number;
  clearNewInvoices: (currentTotal: number) => void;
}

const UserContext = createContext<UserContextValue>({
  user: null,
  setUser: () => {},
  userId: '',
  logout: () => {},
  isLoaded: false,
  newReceivedCount: 0,
  clearNewInvoices: () => {},
});

const STORAGE_KEY = 'ksef_user';
const LAST_RECEIVED_KEY = 'ksef_last_received_total';

export function UserProvider({ children }: { children: ReactNode }) {
  const [user, setUserState] = useState<AppUser | null>(null);
  const [isLoaded, setIsLoaded] = useState(false);
  const [newReceivedCount, setNewReceivedCount] = useState(0);

  useEffect(() => {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved) {
      try { setUserState(JSON.parse(saved)); } catch {}
    }
    setIsLoaded(true);
  }, []);

  // Sprawdź nowe faktury przychodzące przy załadowaniu usera
  useEffect(() => {
    if (!user) return;
    invoicesApi.list(user.id, { direction: 'RECEIVED', size: 1 })
      .then(data => {
        const lastTotal = parseInt(localStorage.getItem(LAST_RECEIVED_KEY) ?? '0', 10);
        if (data.totalElements > lastTotal) {
          setNewReceivedCount(data.totalElements - lastTotal);
        }
      })
      .catch(() => {});
  }, [user?.id]);

  const setUser = (u: AppUser | null) => {
    setUserState(u);
    if (u) localStorage.setItem(STORAGE_KEY, JSON.stringify(u));
    else localStorage.removeItem(STORAGE_KEY);
  };

  const logout = () => {
    setUserState(null);
    setNewReceivedCount(0);
    localStorage.removeItem(STORAGE_KEY);
    localStorage.removeItem(LAST_RECEIVED_KEY);
  };

  const clearNewInvoices = (currentTotal: number) => {
    setNewReceivedCount(0);
    localStorage.setItem(LAST_RECEIVED_KEY, String(currentTotal));
  };

  return (
    <UserContext.Provider value={{ user, setUser, userId: user?.id ?? '', logout, isLoaded, newReceivedCount, clearNewInvoices }}>
      {children}
    </UserContext.Provider>
  );
}

export const useUser = () => useContext(UserContext);
