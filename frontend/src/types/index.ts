export type InvoiceDirection = 'ISSUED' | 'RECEIVED';
export type InvoiceStatus   = 'DRAFT' | 'QUEUED' | 'SENDING' | 'SENT' | 'FAILED' | 'RECEIVED_FROM_KSEF';
export type InvoiceSource   = 'FORM' | 'XLSX' | 'KSEF';

/** Rodzaj faktury zgodny z FA(3) KSeF */
export type RodzajFaktury = 'VAT' | 'KOR' | 'ZAL' | 'ROZ' | 'UPR' | 'KOR_ZAL' | 'KOR_ROZ';

/** Kody stawek VAT zgodne z TStawkaPodatku FA(3) */
export type VatRateCode = '23' | '22' | '8' | '7' | '5' | '4' | '3' | '0 KR' | '0 WDT' | '0 EX' | 'zw' | 'oo' | 'np I' | 'np II';

export const VAT_RATE_CODE_LABELS: Record<VatRateCode, string> = {
  '23':    '23%',
  '22':    '22%',
  '8':     '8%',
  '7':     '7%',
  '5':     '5%',
  '4':     '4%',
  '3':     '3%',
  '0 KR':  '0% (krajowy)',
  '0 WDT': '0% (WDT)',
  '0 EX':  '0% (eksport)',
  'zw':    'zwolniony',
  'oo':    'odwrotne obciążenie',
  'np I':  'np (poza terytorium)',
  'np II': 'np (art. 100)',
};

export const RODZAJ_FAKTURY_LABELS: Record<RodzajFaktury, string> = {
  VAT:     'Faktura VAT',
  KOR:     'Faktura korygująca',
  ZAL:     'Faktura zaliczkowa',
  ROZ:     'Faktura rozliczeniowa',
  UPR:     'Faktura uproszczona',
  KOR_ZAL: 'Korekta faktury zaliczkowej',
  KOR_ROZ: 'Korekta faktury rozliczeniowej',
};

export interface InvoiceItem {
  id: string;
  name: string;
  unit?: string;
  quantity: number;
  netUnitPrice: number;
  vatRate: number;
  vatRateCode?: VatRateCode;
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
  sellerAddress?: string;
  sellerCountryCode: string;
  buyerName: string;
  buyerNip: string;
  buyerAddress?: string;
  buyerCountryCode: string;
  netAmount: number;
  vatAmount: number;
  grossAmount: number;
  currency: string;
  rodzajFaktury: RodzajFaktury;
  metodaKasowa: boolean;
  samofakturowanie: boolean;
  odwrotneObciazenie: boolean;
  mechanizmPodzielonejPlatnosci: boolean;
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
  { key: 'rodzajFaktury',  label: 'Rodzaj faktury' },
  { key: 'sellerName',     label: 'Sprzedawca — nazwa',  required: true },
  { key: 'sellerNip',      label: 'Sprzedawca — NIP',    required: true },
  { key: 'sellerAddress',  label: 'Sprzedawca — adres',  required: true },
  { key: 'sellerCountryCode', label: 'Sprzedawca — kod kraju' },
  { key: 'buyerName',      label: 'Nabywca — nazwa',     required: true },
  { key: 'buyerNip',       label: 'Nabywca — NIP',       required: true },
  { key: 'buyerAddress',   label: 'Nabywca — adres' },
  { key: 'buyerCountryCode', label: 'Nabywca — kod kraju' },
  { key: 'currency',       label: 'Waluta' },
  // Backend XlsxParserService obsługuje item1_* … item10_* (i dalej do 50).
  // Poniżej 10 pozycji pokrywa typowe faktury; dla większych XLSX dodaj kolejne wpisy.
  { key: 'item1_name',         label: 'Pozycja 1 — nazwa',      required: true },
  { key: 'item1_unit',         label: 'Pozycja 1 — j.m.' },
  { key: 'item1_quantity',     label: 'Pozycja 1 — ilość',      required: true },
  { key: 'item1_netUnitPrice', label: 'Pozycja 1 — cena netto', required: true },
  { key: 'item1_vatRate',      label: 'Pozycja 1 — stawka VAT', required: true },
  { key: 'item2_name',         label: 'Pozycja 2 — nazwa' },
  { key: 'item2_unit',         label: 'Pozycja 2 — j.m.' },
  { key: 'item2_quantity',     label: 'Pozycja 2 — ilość' },
  { key: 'item2_netUnitPrice', label: 'Pozycja 2 — cena netto' },
  { key: 'item2_vatRate',      label: 'Pozycja 2 — stawka VAT' },
  { key: 'item3_name',         label: 'Pozycja 3 — nazwa' },
  { key: 'item3_unit',         label: 'Pozycja 3 — j.m.' },
  { key: 'item3_quantity',     label: 'Pozycja 3 — ilość' },
  { key: 'item3_netUnitPrice', label: 'Pozycja 3 — cena netto' },
  { key: 'item3_vatRate',      label: 'Pozycja 3 — stawka VAT' },
  { key: 'item4_name',         label: 'Pozycja 4 — nazwa' },
  { key: 'item4_unit',         label: 'Pozycja 4 — j.m.' },
  { key: 'item4_quantity',     label: 'Pozycja 4 — ilość' },
  { key: 'item4_netUnitPrice', label: 'Pozycja 4 — cena netto' },
  { key: 'item4_vatRate',      label: 'Pozycja 4 — stawka VAT' },
  { key: 'item5_name',         label: 'Pozycja 5 — nazwa' },
  { key: 'item5_unit',         label: 'Pozycja 5 — j.m.' },
  { key: 'item5_quantity',     label: 'Pozycja 5 — ilość' },
  { key: 'item5_netUnitPrice', label: 'Pozycja 5 — cena netto' },
  { key: 'item5_vatRate',      label: 'Pozycja 5 — stawka VAT' },
  { key: 'item6_name',         label: 'Pozycja 6 — nazwa' },
  { key: 'item6_unit',         label: 'Pozycja 6 — j.m.' },
  { key: 'item6_quantity',     label: 'Pozycja 6 — ilość' },
  { key: 'item6_netUnitPrice', label: 'Pozycja 6 — cena netto' },
  { key: 'item6_vatRate',      label: 'Pozycja 6 — stawka VAT' },
  { key: 'item7_name',         label: 'Pozycja 7 — nazwa' },
  { key: 'item7_unit',         label: 'Pozycja 7 — j.m.' },
  { key: 'item7_quantity',     label: 'Pozycja 7 — ilość' },
  { key: 'item7_netUnitPrice', label: 'Pozycja 7 — cena netto' },
  { key: 'item7_vatRate',      label: 'Pozycja 7 — stawka VAT' },
  { key: 'item8_name',         label: 'Pozycja 8 — nazwa' },
  { key: 'item8_unit',         label: 'Pozycja 8 — j.m.' },
  { key: 'item8_quantity',     label: 'Pozycja 8 — ilość' },
  { key: 'item8_netUnitPrice', label: 'Pozycja 8 — cena netto' },
  { key: 'item8_vatRate',      label: 'Pozycja 8 — stawka VAT' },
  { key: 'item9_name',         label: 'Pozycja 9 — nazwa' },
  { key: 'item9_unit',         label: 'Pozycja 9 — j.m.' },
  { key: 'item9_quantity',     label: 'Pozycja 9 — ilość' },
  { key: 'item9_netUnitPrice', label: 'Pozycja 9 — cena netto' },
  { key: 'item9_vatRate',      label: 'Pozycja 9 — stawka VAT' },
  { key: 'item10_name',         label: 'Pozycja 10 — nazwa' },
  { key: 'item10_unit',         label: 'Pozycja 10 — j.m.' },
  { key: 'item10_quantity',     label: 'Pozycja 10 — ilość' },
  { key: 'item10_netUnitPrice', label: 'Pozycja 10 — cena netto' },
  { key: 'item10_vatRate',      label: 'Pozycja 10 — stawka VAT' },
];

export interface AppUser {
  id: string;
  email: string;
  nip: string;
  companyName: string;
  hasKsefToken: boolean;
}
