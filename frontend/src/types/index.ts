export type InvoiceDirection = 'ISSUED' | 'RECEIVED';
export type InvoiceStatus   = 'DRAFT' | 'QUEUED' | 'SENDING' | 'SENT' | 'FAILED' | 'RECEIVED_FROM_KSEF';
export type InvoiceSource   = 'FORM' | 'XLSX';

export interface InvoiceItem {
  id: string;
  name: string;
  unit?: string;
  quantity: number;
  netUnitPrice: number;
  vatRate: number;
  netAmount: number;
  vatAmount: number;
  grossAmount: number;
  position: number;
}

export interface Invoice {
  id: string;
  ksefNumber?: string;
  ksefReferenceNumber?: string;
  direction: InvoiceDirection;
  status: InvoiceStatus;
  invoiceNumber: string;
  issueDate: string;
  saleDate?: string;
  sellerName: string;
  sellerNip: string;
  buyerName: string;
  buyerNip: string;
  netAmount: number;
  vatAmount: number;
  grossAmount: number;
  currency: string;
  errorMessage?: string;
  source: InvoiceSource;
  items: InvoiceItem[];
  createdAt: string;
  updatedAt: string;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// XLSX config types
export type FieldMappingType = 'VALUE' | 'CELL';

export interface FieldMapping {
  type: FieldMappingType;
  value?: string;        // when type=VALUE
  cellRef?: string;      // e.g. "A1" — when type=CELL
  sheetIndex?: number;   // 0-based, default 0
}

export interface XlsxConfig {
  id: string;
  userId: string;
  name: string;
  description?: string;
  fieldMappings: Record<string, FieldMapping>;
  createdAt: string;
  updatedAt: string;
}

// All invoice fields that can be configured via XLSX mapping
export const INVOICE_FIELDS: { key: string; label: string; required?: boolean }[] = [
  { key: 'invoiceNumber',  label: 'Numer faktury',       required: true },
  { key: 'issueDate',      label: 'Data wystawienia',    required: true },
  { key: 'saleDate',       label: 'Data sprzedaży' },
  { key: 'sellerName',     label: 'Sprzedawca — nazwa',  required: true },
  { key: 'sellerNip',      label: 'Sprzedawca — NIP',    required: true },
  { key: 'sellerAddress',  label: 'Sprzedawca — adres' },
  { key: 'buyerName',      label: 'Nabywca — nazwa',     required: true },
  { key: 'buyerNip',       label: 'Nabywca — NIP',       required: true },
  { key: 'buyerAddress',   label: 'Nabywca — adres' },
  { key: 'currency',       label: 'Waluta' },
  { key: 'item1_name',     label: 'Pozycja 1 — nazwa',   required: true },
  { key: 'item1_unit',     label: 'Pozycja 1 — j.m.' },
  { key: 'item1_quantity', label: 'Pozycja 1 — ilość',   required: true },
  { key: 'item1_netUnitPrice', label: 'Pozycja 1 — cena netto', required: true },
  { key: 'item1_vatRate',  label: 'Pozycja 1 — stawka VAT', required: true },
  { key: 'item2_name',     label: 'Pozycja 2 — nazwa' },
  { key: 'item2_unit',     label: 'Pozycja 2 — j.m.' },
  { key: 'item2_quantity', label: 'Pozycja 2 — ilość' },
  { key: 'item2_netUnitPrice', label: 'Pozycja 2 — cena netto' },
  { key: 'item2_vatRate',  label: 'Pozycja 2 — stawka VAT' },
];

export interface AppUser {
  id: string;
  email: string;
  nip: string;
  companyName: string;
  hasKsefToken: boolean;
}
