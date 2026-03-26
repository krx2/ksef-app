'use client';

import { createContext, useContext, useState, useEffect, ReactNode } from 'react';
import type { AppUser } from '@/types';

interface UserContextValue {
  user: AppUser | null;
  setUser: (u: AppUser | null) => void;
  userId: string;
}

const UserContext = createContext<UserContextValue>({
  user: null,
  setUser: () => {},
  userId: '',
});

const STORAGE_KEY = 'ksef_user';

export function UserProvider({ children }: { children: ReactNode }) {
  const [user, setUserState] = useState<AppUser | null>(null);

  useEffect(() => {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved) {
      try { setUserState(JSON.parse(saved)); } catch {}
    }
  }, []);

  const setUser = (u: AppUser | null) => {
    setUserState(u);
    if (u) localStorage.setItem(STORAGE_KEY, JSON.stringify(u));
    else localStorage.removeItem(STORAGE_KEY);
  };

  return (
    <UserContext.Provider value={{ user, setUser, userId: user?.id ?? '' }}>
      {children}
    </UserContext.Provider>
  );
}

export const useUser = () => useContext(UserContext);
