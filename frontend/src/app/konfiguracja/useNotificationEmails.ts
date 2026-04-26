'use client';

import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { notificationEmailsApi } from '@/lib/api';
import type { NotificationEmail } from '@/types';

export function useNotificationEmails(userId: string) {
  const qc = useQueryClient();

  const { data: emails, refetch } = useQuery<NotificationEmail[]>({
    queryKey: ['notification-emails', userId],
    queryFn:  () => notificationEmailsApi.list(userId),
    enabled:  !!userId,
  });

  const [newEmail, setNewEmail] = useState('');
  const [newLabel, setNewLabel] = useState('');
  const [adding, setAdding]     = useState(false);
  const [error, setError]       = useState('');

  const add = async () => {
    if (!newEmail.trim()) return;
    setAdding(true);
    setError('');
    try {
      await notificationEmailsApi.create(userId, newEmail.trim(), newLabel.trim() || undefined);
      setNewEmail('');
      setNewLabel('');
      refetch();
    } catch (err: unknown) {
      const axiosErr = err as { response?: { data?: { error?: string } } };
      setError(axiosErr?.response?.data?.error ?? 'Błąd dodawania adresu');
    } finally {
      setAdding(false);
    }
  };

  const remove = async (id: string) => {
    if (!confirm('Na pewno usunąć ten adres z listy powiadomień?')) return;
    await notificationEmailsApi.delete(userId, id);
    refetch();
  };

  return { emails, newEmail, setNewEmail, newLabel, setNewLabel, adding, error, add, remove };
}
