import axios from 'axios';
import type { Invoice, InvoiceDirection, PageResponse, XlsxConfig, AppUser } from '@/types';

// TODO: Brak timeoutu — żądania mogą wisieć w nieskończoność (np. gdy backend nie odpowiada).
//       Dodać: timeout: 30_000 (30 s) do konfiguracji axios.create().
//       Dla uploadu plików XLSX użyć wyższego timeoutu (np. 120 s) przekazywanego per-request.
// TODO: Brak globalnych interceptorów:
//       - Request interceptor: dołączyć token JWT gdy zostanie wprowadzona autentykacja.
//       - Response interceptor: ujednolicić obsługę błędów (np. przekierowanie na /login przy 401,
//         wyświetlenie toastu przy 5xx) zamiast obsługiwania ich w każdym komponencie osobno.
const api = axios.create({
  baseURL: '/api/backend',
  headers: { 'Content-Type': 'application/json' },
});

// TODO: X-User-Id jako nagłówek bez JWT to wyłącznie mechanizm deweloperski — użytkownik
//       może go podmienić w DevTools i działać jako inny użytkownik.
//       Docelowo: usunąć X-User-Id, identyfikować użytkownika wyłącznie po JWT w Authorization header,
//       a userId pobierać z backendu na podstawie tokenu (endpoint GET /api/users/me).
// Temporary: hardcode userId header until auth is implemented
// In production this would come from a session/JWT
const USER_ID_HEADER = 'X-User-Id';

function withUser(userId: string) {
  return { headers: { [USER_ID_HEADER]: userId } };
}

// ---- Invoices ----

export const invoicesApi = {
  list: (userId: string, params?: { direction?: InvoiceDirection; page?: number; size?: number }) =>
    api.get<PageResponse<Invoice>>('/invoices', {
      ...withUser(userId),
      params,
    }).then(r => r.data),

  get: (userId: string, id: string) =>
    api.get<Invoice>(`/invoices/${id}`, withUser(userId)).then(r => r.data),

  createFromForm: (userId: string, data: unknown) =>
    api.post<Invoice>('/invoices', data, withUser(userId)).then(r => r.data),

  createFromXlsx: (userId: string, file: File, configId: string) => {
    const form = new FormData();
    form.append('file', file);
    form.append('configId', configId);
    // TODO: Brak walidacji rozszerzenia pliku po stronie klienta przed wysłaniem.
    //       Backend przyjmuje dowolny plik — podanie pliku CSV lub PDF skończy się
    //       błędem HTTP 400 z mało czytelnym komunikatem z Apache POI.
    //       Dodać: if (!file.name.match(/\.(xlsx|xls)$/i)) throw new Error('Plik musi być w formacie .xlsx lub .xls')
    return api.post<Invoice>('/invoices/from-xlsx', form, {
      headers: { [USER_ID_HEADER]: userId, 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data);
  },

  previewXlsx: (userId: string, file: File, configId: string) => {
    const form = new FormData();
    form.append('file', file);
    form.append('configId', configId);
    return api.post<Record<string, string>>('/invoices/xlsx-preview', form, {
      headers: { [USER_ID_HEADER]: userId, 'Content-Type': 'multipart/form-data' },
    }).then(r => r.data);
  },

  /** Send invoice data (already parsed & edited by user) as plain JSON. */
  createFromParsed: (userId: string, data: unknown) =>
    api.post<Invoice>('/invoices', data, withUser(userId)).then(r => r.data),
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
      { headers: { [USER_ID_HEADER]: userId, 'Content-Type': 'multipart/form-data' } }
    ).then(r => r.data);
  },
};

// ---- Users ----

export const usersApi = {
  get: (id: string) =>
    api.get<AppUser>(`/users/${id}`).then(r => r.data),

  create: (data: { email: string; nip: string; companyName: string; ksefToken?: string }) =>
    api.post<AppUser>('/users', data).then(r => r.data),

  updateToken: (id: string, ksefToken: string) =>
    api.put(`/users/${id}/ksef-token`, { ksefToken }),
};
