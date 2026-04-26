'use client';

import { userPrefixApi } from '@/lib/api';
import { useUser } from '@/lib/user-context';

export function usePrefixMode() {
  const { userId, user, setUser } = useUser();

  const updatePrefixMode = async (mode: 'NONE' | 'YEAR_MONTH') => {
    if (!userId || !user) return;
    await userPrefixApi.updatePrefixMode(userId, mode);
    setUser({ ...user, invoicePrefixMode: mode });
  };

  return { prefixMode: user?.invoicePrefixMode ?? 'NONE', updatePrefixMode };
}
