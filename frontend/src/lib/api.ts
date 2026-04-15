import axios, { AxiosError } from 'axios';
import type { Invoice, InvoiceFilters, PageResponse, XlsxConfig, AppUser } from '@/types';

// X-User-Id jako nagłówek bez JWT to wyłącznie mechanizm deweloperski — użytkownik
// może go podmienić w DevTools i działać jako inny użytkownik.
// Docelowo: usunąć X-User-Id, identyfikować użytkownika wyłącznie po JWT w Authorization header,
// a userId pobierać z backendu na podstawie tokenu (endpoint GET /api/users/me).
const USER_ID_HEADER = 'X-User-Id';

/** Data startu systemu KSeF 2.0 — domyślny dolny zakres filtru dat na liście faktur. */
export const KSEF_HISTORY_START = '2026-02-01';

const api = axios.create({
  baseURL: '/api/backend',
  headers: { 'Content-Type': 'application/json' },
  timeout: 30_000,
});

// Response interceptor: ujednolicona obsługa błędów sieciowych.
// Błędy HTTP (4xx/5xx) są propagowane dalej — obsługuje je każdy komponent osobno.
api.interceptors.response.use(
  response => response,
  (error: AxiosError) => {
    if (error.code === 'ECONNABORTED') {
      return Promise.reject(
        Object.assign(new Error('Przekroczono limit czasu połączenia z serwerem (30 s).'), { isTimeout: true })
      );
    }
    if (error.code === 'ERR_NETWORK') {
      return Promise.reject(
        Object.assign(new Error('Brak połączenia z serwerem. Sprawdź połączenie sieciowe.'), { isNetwork: true })
      );
    }
    return Promise.reject(error);
  }
);

function withUser(userId: string) {
  return { headers: { [USER_ID_HEADER]: userId } };
}

function withFileUpload(userId: string, signal?: AbortSignal) {
  return {
    headers: { [USER_ID_HEADER]: userId, 'Content-Type': 'multipart/form-data' },
    timeout: 120_000,
    signal,
  };
}

function validateXlsxFile(file: File) {
  if (!file.name.match(/\.(xlsx|xls)$/i)) {
    throw Object.assign(
      new Error('Plik musi być w formacie .xlsx lub .xls'),
      { isValidation: true }
    );
  }
}

// ---- Invoices ----

export const invoicesApi = {
  list: (userId: string, filters?: InvoiceFilters) =>
    api.get<PageResponse<Invoice>>('/invoices', {
      ...withUser(userId),
      params: filters,
    }).then(r => r.data),

  get: (userId: string, id: string) =>
    api.get<Invoice>(`/invoices/${id}`, withUser(userId)).then(r => r.data),

  createFromForm: (userId: string, data: unknown) =>
    api.post<Invoice>('/invoices', data, withUser(userId)).then(r => r.data),

  createFromXlsx: (userId: string, file: File, configId: string) => {
    validateXlsxFile(file);
    const form = new FormData();
    form.append('file', file);
    form.append('configId', configId);
    return api.post<Invoice>('/invoices/from-xlsx', form, withFileUpload(userId)).then(r => r.data);
  },

  previewXlsx: (userId: string, file: File, configId: string, signal?: AbortSignal) => {
    validateXlsxFile(file);
    const form = new FormData();
    form.append('file', file);
    form.append('configId', configId);
    return api.post<Record<string, string>>(
      '/invoices/xlsx-preview',
      form,
      withFileUpload(userId, signal)
    ).then(r => r.data);
  },

  /** Send invoice data (already parsed & edited by user) as plain JSON. */
  createFromParsed: (userId: string, data: unknown) =>
    api.post<Invoice>('/invoices', data, withUser(userId)).then(r => r.data),

  fetchFromKsef: (userId: string) =>
    api.post<{ message: string }>('/invoices/fetch', null, withUser(userId)).then(r => r.data),

  fetchHistoryFromKsef: (userId: string) =>
    api.post<{ message: string }>('/invoices/fetch-history', null, withUser(userId)).then(r => r.data),

  // TODO(F7): Pobieranie oficjalnego PDF faktury z KSeF.
  //   Aktywować gdy backend (InvoiceController GET /{id}/pdf + KsefPdfService) będzie gotowy.
  //   Metoda zwraca Blob gotowy do pobrania przez użytkownika.
  //
  // downloadPdf: (userId: string, invoiceId: string) =>
  //   api.get(`/invoices/${invoiceId}/pdf`, {
  //     ...withUser(userId),
  //     responseType: 'blob',
  //   }).then(r => {
  //     const url = URL.createObjectURL(new Blob([r.data], { type: 'application/pdf' }));
  //     const a = document.createElement('a');
  //     a.href = url;
  //     a.download = `faktura-${invoiceId}.pdf`;
  //     a.click();
  //     URL.revokeObjectURL(url);
  //   }),
};

// ---- XLSX Configurations ----

export const xlsxConfigsApi = {
  list: (userId: string) =>
    api.get<XlsxConfig[]>('/xlsx-configs', withUser(userId)).then(r => r.data),

  get: (userId: string, id: string) =>
    api.get<XlsxConfig>(`/xlsx-configs/${id}`, withUser(userId)).then(r => r.data),

  create: (userId: string, data: unknown) =>
    api.post<XlsxConfig>('/xlsx-configs', data, withUser(userId)).then(r => r.data),

  update: (userId: string, id: string, data: unknown) =>
    api.put<XlsxConfig>(`/xlsx-configs/${id}`, data, withUser(userId)).then(r => r.data),

  delete: (userId: string, id: string) =>
    api.delete(`/xlsx-configs/${id}`, withUser(userId)),

  testCell: (userId: string, file: File, cellRef: string, sheetIndex = 0) => {
    const form = new FormData();
    form.append('file', file);
    return api.post<{ cellRef: string; value: string }>(
      `/xlsx-configs/test-cell?cellRef=${cellRef}&sheetIndex=${sheetIndex}`,
      form,
      withFileUpload(userId)
    ).then(r => r.data);
  },
};

// ---- Users ----

export const usersApi = {
  get: (id: string) =>
    api.get<AppUser>(`/users/${id}`).then(r => r.data),

  loginByNip: (nip: string) =>
    api.get<AppUser>(`/users/by-nip/${nip}`).then(r => r.data),

  create: (data: { email: string; nip: string; companyName: string; ksefToken?: string; invoicePrefixMode?: 'NONE' | 'YEAR_MONTH' }) =>
    api.post<AppUser>('/users', data).then(r => r.data),

  updateToken: (id: string, ksefToken: string) =>
    api.put(`/users/${id}/ksef-token`, { ksefToken }),
};

// ---- Config ----

export type KsefEnvironment = 'test' | 'prod';

export const configApi = {
  get: () =>
    api.get<{ ksefEnvironment: KsefEnvironment }>('/config').then(r => r.data),
};

export const notificationEmailsApi = {
  /** Pobiera listę adresów powiadomień użytkownika (posortowaną wg sort_order). */
  list: (userId: string) =>
    api.get<import('@/types').NotificationEmail[]>('/notification-emails', withUser(userId))
      .then(r => r.data),

  /** Dodaje nowy adres email. Zwraca 400 gdy adres już istnieje. */
  add: (userId: string, email: string, label?: string) =>
    api.post<import('@/types').NotificationEmail>(
      '/notification-emails',
      { email, label },
      withUser(userId)
    ).then(r => r.data),

  /** Aktualizuje etykietę lub kolejność adresu. */
  update: (userId: string, id: string, patch: { label?: string; sortOrder?: number }) =>
    api.put<import('@/types').NotificationEmail>(
      `/notification-emails/${id}`,
      patch,
      withUser(userId)
    ).then(r => r.data),

  /** Usuwa adres z listy powiadomień. */
  remove: (userId: string, id: string) =>
    api.delete(`/notification-emails/${id}`, withUser(userId)),
};

export const userPrefixApi = {
  updatePrefixMode: (userId: string, mode: 'NONE' | 'YEAR_MONTH') =>
    api.put(`/users/${userId}/invoice-prefix-mode`, { invoicePrefixMode: mode }, withUser(userId)),
};

export const reportsApi = {
  /** Pobiera listę faktur z danego miesiąca (format YYYY-MM) do wyświetlenia checkboxów. */
  listForMonth: (userId: string, month: string) =>
    api.get<import('@/types').Invoice[]>('/reports/invoices', {
      ...withUser(userId),
      params: { month },
    }).then(r => r.data),

  /** Generuje PDF raportu dla zaznaczonych faktur i triggeruje pobranie pliku. */
  generatePdf: (userId: string, month: string, invoiceIds: string[]) =>
    api.post('/reports/monthly-pdf', { month, invoiceIds }, {
      ...withUser(userId),
      responseType: 'blob',
    }).then(r => {
      const url = URL.createObjectURL(new Blob([r.data], { type: 'application/pdf' }));
      const a = document.createElement('a');
      a.href = url;
      a.download = `raport-${month}.pdf`;
      a.click();
      URL.revokeObjectURL(url);
    }),
};
