import axios from 'axios';
import type { Invoice, InvoiceDirection, PageResponse, XlsxConfig, AppUser } from '@/types';

const api = axios.create({
  baseURL: '/api/backend',
  headers: { 'Content-Type': 'application/json' },
});

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
