'use client';

import { useState, useRef, useEffect, useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { Upload, Send, FileSpreadsheet, RefreshCw, AlertCircle } from 'lucide-react';
import { useUser } from '@/lib/user-context';
import { invoicesApi, xlsxConfigsApi } from '@/lib/api';
import { RodzajFaktury, RODZAJ_FAKTURY_LABELS } from '@/types';
import Link from 'next/link';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface ItemFields {
  name: string;
  unit: string;
  quantity: string;
  netUnitPrice: string;
  vatRate: string;
}

interface ParsedFields {
  invoiceNumber: string;
  issueDate: string;
  saleDate: string;
  rodzajFaktury: string;
  sellerName: string;
  sellerNip: string;
  sellerAddress: string;
  buyerName: string;
  buyerNip: string;
  buyerAddress: string;
  currency: string;
  items: ItemFields[];
}

interface Annotations {
  metodaKasowa: boolean;
  samofakturowanie: boolean;
  odwrotneObciazenie: boolean;
  mechanizmPodzielonejPlatnosci: boolean;
}

type TopKey = keyof Omit<ParsedFields, 'items'>;
type FieldErrors = Partial<Record<TopKey, string>> & {
  items?: Partial<Record<keyof ItemFields, string>>[];
};

// ---------------------------------------------------------------------------
// Validation — mirrors backend Bean Validation constraints
// ---------------------------------------------------------------------------

const NIP_RE      = /^\d{10}$/;
const DATE_RE     = /^\d{4}-\d{2}-\d{2}$/;
const CURRENCY_RE = /^[A-Z]{3}$/;

function validateFields(f: ParsedFields): FieldErrors {
  const e: FieldErrors = {};

  if (!f.invoiceNumber.trim())
    e.invoiceNumber = 'Numer faktury jest wymagany';

  if (!f.issueDate.trim())
    e.issueDate = 'Data wystawienia jest wymagana';
  else if (!DATE_RE.test(f.issueDate.trim()))
    e.issueDate = 'Format daty: RRRR-MM-DD (np. 2024-03-15)';

  if (f.saleDate.trim() && !DATE_RE.test(f.saleDate.trim()))
    e.saleDate = 'Format daty: RRRR-MM-DD (np. 2024-03-15)';

  if (!f.sellerName.trim())
    e.sellerName = 'Nazwa sprzedawcy jest wymagana';

  if (!f.sellerNip.trim())
    e.sellerNip = 'NIP sprzedawcy jest wymagany';
  else if (!NIP_RE.test(f.sellerNip.replace(/[- ]/g, '')))
    e.sellerNip = 'NIP musi składać się z dokładnie 10 cyfr';

  // FA(3): adres sprzedawcy wymagany (Podmiot1.Adres.AdresL1)
  if (!f.sellerAddress.trim())
    e.sellerAddress = 'Adres sprzedawcy jest wymagany w FA(3)';

  if (!f.buyerName.trim())
    e.buyerName = 'Nazwa nabywcy jest wymagana';

  if (!f.buyerNip.trim())
    e.buyerNip = 'NIP nabywcy jest wymagany';
  else if (!NIP_RE.test(f.buyerNip.replace(/[- ]/g, '')))
    e.buyerNip = 'NIP musi składać się z dokładnie 10 cyfr';

  if (f.currency.trim() && !CURRENCY_RE.test(f.currency.trim()))
    e.currency = 'Waluta: 3 wielkie litery (np. PLN, EUR)';

  const itemErrors: Partial<Record<keyof ItemFields, string>>[] = f.items.map(item => {
    const ie: Partial<Record<keyof ItemFields, string>> = {};
    if (!item.name.trim())
      ie.name = 'Nazwa pozycji jest wymagana';
    const qty = parseFloat(item.quantity.replace(',', '.'));
    if (!item.quantity.trim() || isNaN(qty) || qty <= 0)
      ie.quantity = 'Ilość musi być liczbą większą od 0';
    const price = parseFloat(item.netUnitPrice.replace(',', '.'));
    if (!item.netUnitPrice.trim() || isNaN(price) || price <= 0)
      ie.netUnitPrice = 'Cena netto musi być liczbą większą od 0';
    const vat = parseFloat(item.vatRate.replace(',', '.'));
    if (!item.vatRate.trim() || isNaN(vat) || vat < 0 || vat > 100)
      ie.vatRate = 'Stawka VAT: liczba od 0 do 100';
    return ie;
  });

  if (itemErrors.some(ie => Object.keys(ie).length > 0))
    e.items = itemErrors;

  return e;
}

function countErrors(e: FieldErrors): number {
  const { items, ...top } = e;
  return Object.keys(top).length +
    (items?.reduce((s, ie) => s + Object.keys(ie).length, 0) ?? 0);
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function emptyItem(): ItemFields {
  return { name: '', unit: '', quantity: '', netUnitPrice: '', vatRate: '' };
}

function emptyFields(): ParsedFields {
  return {
    invoiceNumber: '', issueDate: '', saleDate: '',
    rodzajFaktury: 'VAT',
    sellerName: '', sellerNip: '', sellerAddress: '',
    buyerName: '', buyerNip: '', buyerAddress: '',
    currency: 'PLN', items: [emptyItem()],
  };
}

/** Convert flat preview map { item1_name: ..., item2_name: ... } → ParsedFields */
function mapToFields(raw: Record<string, string>): ParsedFields {
  const f = emptyFields();
  f.invoiceNumber  = raw.invoiceNumber  ?? '';
  f.issueDate      = raw.issueDate      ?? '';
  f.saleDate       = raw.saleDate       ?? '';
  f.rodzajFaktury  = raw.rodzajFaktury  ?? 'VAT';
  f.sellerName     = raw.sellerName     ?? '';
  f.sellerNip      = raw.sellerNip      ?? '';
  f.sellerAddress  = raw.sellerAddress  ?? '';
  f.buyerName      = raw.buyerName      ?? '';
  f.buyerNip       = raw.buyerNip       ?? '';
  f.buyerAddress   = raw.buyerAddress   ?? '';
  f.currency       = raw.currency       ?? 'PLN';

  const items: ItemFields[] = [];
  for (let i = 1; i <= 50; i++) {
    const name = raw[`item${i}_name`];
    if (!name) break;
    items.push({
      name,
      unit:         raw[`item${i}_unit`]         ?? '',
      quantity:     raw[`item${i}_quantity`]     ?? '',
      netUnitPrice: raw[`item${i}_netUnitPrice`] ?? '',
      vatRate:      raw[`item${i}_vatRate`]      ?? '',
    });
  }
  f.items = items.length > 0 ? items : [emptyItem()];
  return f;
}

/** Build the JSON payload expected by POST /api/invoices */
function toCreateRequest(f: ParsedFields, annotations: Annotations) {
  return {
    invoiceNumber: f.invoiceNumber.trim(),
    issueDate:     f.issueDate.trim(),
    saleDate:      f.saleDate.trim() || null,
    rodzajFaktury: f.rodzajFaktury || 'VAT',
    sellerName:    f.sellerName.trim(),
    sellerNip:     f.sellerNip.replace(/[- ]/g, ''),
    sellerAddress: f.sellerAddress.trim(),  // wymagany w FA(3)
    // TODO: Brak pól sellerCountryCode i buyerCountryCode w tym formularzu.
    //       Są one wymagane przez FA(3) (Podmiot1.Adres.KodKraju / Podmiot2.Adres.KodKraju).
    //       Backend domyślnie ustawia "PL", ale gdy nabywca jest zagraniczny (kod != PL),
    //       użytkownik nie ma możliwości tego zmienić przez ścieżkę XLSX.
    //       Dodać pola sellerCountryCode i buyerCountryCode do ParsedFields, mapToFields(),
    //       formularza (FieldRow) i toCreateRequest().
    buyerName:     f.buyerName.trim(),
    buyerNip:      f.buyerNip.replace(/[- ]/g, ''),
    buyerAddress:  f.buyerAddress.trim() || null,
    currency:      f.currency.trim() || 'PLN',
    ...annotations,
    items: f.items.map(item => ({
      name:         item.name.trim(),
      unit:         item.unit.trim() || null,
      quantity:     parseFloat(item.quantity.replace(',', '.')),
      netUnitPrice: parseFloat(item.netUnitPrice.replace(',', '.')),
      // TODO: vatRate jest wysyłany jako liczba (np. 23.0), ale nie wysyłamy vatRateCode.
      //       Oznacza to, że kody specjalne takie jak "zw", "oo", "np I" nie mogą być
      //       ustawione przez ścieżkę XLSX — backend zawsze obliczy VAT numerycznie.
      //       Dodać pole vatRateCode do ItemFields, mapToFields() (z kluczy item1_vatRateCode)
      //       i przesłać je tutaj obok vatRate.
      vatRate:      parseFloat(item.vatRate.replace(',', '.')),
    })),
  };
}

// ---------------------------------------------------------------------------
// FieldRow — reusable editable row with inline error
// ---------------------------------------------------------------------------

function FieldRow({
  label, required, value, error, onChange, placeholder, mono = false,
}: {
  label: string; required?: boolean; value: string; error?: string;
  onChange: (v: string) => void; placeholder?: string; mono?: boolean;
}) {
  const invalid = !!error;
  return (
    <div className="grid grid-cols-12 items-start gap-2 py-2 border-b border-gray-50 last:border-0">
      <label className="col-span-4 text-sm text-gray-600 pt-1.5 leading-tight">
        {label}{required && <span className="text-red-400 ml-0.5">*</span>}
      </label>
      <div className="col-span-8 space-y-0.5">
        <input
          className={[
            'input py-1 text-sm w-full',
            mono ? 'font-mono' : '',
            invalid ? 'border-red-400 bg-red-50 focus:ring-red-200 focus:border-red-400' : '',
          ].join(' ')}
          value={value}
          onChange={e => onChange(e.target.value)}
          placeholder={placeholder}
        />
        {invalid && (
          <p className="text-xs text-red-500 flex items-center gap-1">
            <AlertCircle size={11} className="shrink-0" />
            {error}
          </p>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

export default function XlsxUploadPage() {
  const router = useRouter();
  const { userId } = useUser();
  const fileRef = useRef<HTMLInputElement>(null);

  const [file, setFile]         = useState<File | null>(null);
  const [configId, setConfigId] = useState('');
  const [fields, setFields]     = useState<ParsedFields>(emptyFields());
  const [annotations, setAnnotations] = useState<Annotations>({
    metodaKasowa: false,
    samofakturowanie: false,
    odwrotneObciazenie: false,
    mechanizmPodzielonejPlatnosci: false,
  });
  const [errors, setErrors]     = useState<FieldErrors>({});
  const [touched, setTouched]   = useState(false);
  const [loadingPreview, setLoadingPreview] = useState(false);
  const [loadingSend, setLoadingSend]       = useState(false);
  const [apiError, setApiError] = useState('');

  const { data: configs } = useQuery({
    queryKey: ['xlsx-configs', userId],
    queryFn:  () => xlsxConfigsApi.list(userId),
    enabled:  !!userId,
  });

  // Keep errors in sync whenever fields change
  useEffect(() => {
    setErrors(validateFields(fields));
  }, [fields]);

  // Fetch preview automatically when both inputs are ready
  const fetchPreview = useCallback(async (f: File, cId: string) => {
    if (!f || !cId || !userId) return;
    // TODO: Race condition — jeśli użytkownik szybko zmieni plik, a potem konfigurację,
    //       dwa wywołania fetchPreview działają równolegle. Odpowiedź drugiego może nadpisać
    //       wynik pierwszego jeśli serwer odpowie w odwrotnej kolejności.
    //       Naprawić przez AbortController: trzymać ref do aktualnego controllera,
    //       anulować poprzednie żądanie przed każdym nowym wywołaniem:
    //         const abortCtrl = new AbortController();
    //         abortCtrlRef.current?.abort();
    //         abortCtrlRef.current = abortCtrl;
    //       a następnie przekazać signal do axios: { signal: abortCtrl.signal }
    setLoadingPreview(true);
    setApiError('');
    try {
      const raw = await invoicesApi.previewXlsx(userId, f, cId);
      setFields(mapToFields(raw));
      setTouched(true);   // show validation state immediately after parse
    } catch (err: any) {
      setApiError(err?.response?.data?.error ?? 'Błąd odczytu pliku');
    } finally {
      setLoadingPreview(false);
    }
  }, [userId]);

  const handleFileChange = (f: File | null) => {
    setFile(f);
    setTouched(false);
    if (f && configId) fetchPreview(f, configId);
  };

  const handleConfigChange = (cId: string) => {
    setConfigId(cId);
    setTouched(false);
    if (file && cId) fetchPreview(file, cId);
  };

  // Field setters
  const setTop = (key: TopKey, val: string) =>
    setFields(prev => ({ ...prev, [key]: val }));

  const setItemField = (idx: number, key: keyof ItemFields, val: string) =>
    setFields(prev => {
      const items = [...prev.items];
      items[idx] = { ...items[idx], [key]: val };
      return { ...prev, items };
    });

  const addItem = () =>
    setFields(prev => ({ ...prev, items: [...prev.items, emptyItem()] }));

  const removeItem = (idx: number) =>
    setFields(prev => ({ ...prev, items: prev.items.filter((_, i) => i !== idx) }));

  const handleSend = async () => {
    setTouched(true);
    const errs = validateFields(fields);
    setErrors(errs);
    if (countErrors(errs) > 0) return;

    setLoadingSend(true);
    setApiError('');
    try {
      await invoicesApi.createFromParsed(userId, toCreateRequest(fields, annotations));
      router.push('/faktury');
    } catch (err: any) {
      setApiError(
        err?.response?.data?.error ??
        err?.response?.data?.message ??
        'Błąd wysyłania faktury'
      );
    } finally {
      setLoadingSend(false);
    }
  };

  const isReady  = !!file && !!configId;
  const errCount = touched ? countErrors(errors) : 0;

  return (
    <div className="space-y-6 max-w-3xl">

      {/* Page header */}
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Faktura z pliku XLS / XLSX</h1>
        <Link href="/faktury" className="btn-secondary">← Powrót</Link>
      </div>

      {/* API / server error */}
      {apiError && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-3 text-sm text-red-700 flex items-center gap-2">
          <AlertCircle size={15} className="shrink-0" />
          {apiError}
        </div>
      )}

      {/* Step selectors */}
      <div className="grid grid-cols-2 gap-4">
        {/* Config picker */}
        <div className="card p-4 space-y-2">
          <h2 className="font-medium text-gray-700 text-sm">1. Konfiguracja XLSX</h2>
          {configs?.length === 0 ? (
            <p className="text-xs text-gray-500">
              Brak konfiguracji.{' '}
              <Link href="/konfiguracja" className="text-brand-600 underline">Utwórz →</Link>
            </p>
          ) : (
            <select
              className="input text-sm"
              value={configId}
              onChange={e => handleConfigChange(e.target.value)}
            >
              <option value="">— wybierz —</option>
              {configs?.map(c => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          )}
        </div>

        {/* File picker */}
        <div className="card p-4 space-y-2">
          <h2 className="font-medium text-gray-700 text-sm">2. Plik XLS / XLSX</h2>
          <div
            className={[
              'border-2 border-dashed rounded-lg p-4 text-center cursor-pointer transition-colors',
              file ? 'border-green-300 bg-green-50' : 'border-gray-200 hover:border-brand-400',
            ].join(' ')}
            onClick={() => fileRef.current?.click()}
          >
            <FileSpreadsheet
              size={22}
              className={`mx-auto mb-1 ${file ? 'text-green-500' : 'text-gray-300'}`}
            />
            {file
              ? <p className="text-xs font-medium text-green-700">{file.name}</p>
              : <p className="text-xs text-gray-400">Kliknij aby wybrać plik</p>
            }
          </div>
          <input
            ref={fileRef}
            type="file"
            accept=".xlsx,.xls"
            className="hidden"
            onChange={e => handleFileChange(e.target.files?.[0] ?? null)}
          />
        </div>
      </div>

      {/* Loading spinner */}
      {loadingPreview && (
        <div className="flex items-center gap-2 text-sm text-brand-600">
          <RefreshCw size={14} className="animate-spin" />
          Wczytuję dane z pliku…
        </div>
      )}

      {/* Empty state */}
      {!isReady && !loadingPreview && (
        <div className="card p-10 text-center text-gray-400">
          <Upload size={32} className="mx-auto mb-3 text-gray-200" />
          <p className="text-sm">Wybierz konfigurację i plik, aby wczytać dane faktury.</p>
        </div>
      )}

      {/* ------------------------------------------------------------------ */}
      {/* Editable preview — shown as soon as file + config are set           */}
      {/* ------------------------------------------------------------------ */}
      {isReady && !loadingPreview && (
        <>
          {/* Validation summary banner */}
          {touched && errCount > 0 && (
            <div className="bg-amber-50 border border-amber-200 rounded-lg px-4 py-3 flex items-center gap-2 text-sm text-amber-800">
              <AlertCircle size={15} className="shrink-0 text-amber-500" />
              Znaleziono <strong className="mx-1">{errCount}</strong>
              {errCount === 1 ? 'błąd' : 'błędy / błędów'} — popraw podświetlone pola przed wysłaniem.
            </div>
          )}

          {/* ---- Invoice header ---- */}
          <div className="card overflow-hidden">
            <div className="px-5 py-3 border-b border-gray-100 flex items-center justify-between">
              <h3 className="font-medium text-gray-700 text-sm">Dane faktury</h3>
              <button
                type="button"
                className="btn-secondary py-1 text-xs flex items-center gap-1"
                disabled={loadingPreview}
                onClick={() => { setTouched(false); fetchPreview(file!, configId); }}
              >
                <RefreshCw size={11} />
                Wczytaj ponownie
              </button>
            </div>
            <div className="px-5 py-2">
              <FieldRow
                label="Numer faktury" required
                value={fields.invoiceNumber}
                error={touched ? errors.invoiceNumber : undefined}
                onChange={v => setTop('invoiceNumber', v)}
                placeholder="np. FV/2024/001"
                mono
              />
              <FieldRow
                label="Data wystawienia" required
                value={fields.issueDate}
                error={touched ? errors.issueDate : undefined}
                onChange={v => setTop('issueDate', v)}
                placeholder="RRRR-MM-DD"
                mono
              />
              <FieldRow
                label="Data sprzedaży"
                value={fields.saleDate}
                error={touched ? errors.saleDate : undefined}
                onChange={v => setTop('saleDate', v)}
                placeholder="RRRR-MM-DD (opcjonalnie)"
                mono
              />
              <FieldRow
                label="Waluta"
                value={fields.currency}
                error={touched ? errors.currency : undefined}
                onChange={v => setTop('currency', v.toUpperCase())}
                placeholder="PLN"
                mono
              />
              {/* Rodzaj faktury FA(3) */}
              <div className="grid grid-cols-12 items-start gap-2 py-2 border-b border-gray-50">
                <label className="col-span-4 text-sm text-gray-600 pt-1.5">
                  Rodzaj faktury
                </label>
                <div className="col-span-8">
                  <select
                    className="input py-1 text-sm w-full"
                    value={fields.rodzajFaktury}
                    onChange={e => setTop('rodzajFaktury', e.target.value)}
                  >
                    {(Object.keys(RODZAJ_FAKTURY_LABELS) as RodzajFaktury[]).map(k => (
                      <option key={k} value={k}>{RODZAJ_FAKTURY_LABELS[k]}</option>
                    ))}
                  </select>
                </div>
              </div>
              {/* Adnotacje FA(3) */}
              <div className="grid grid-cols-12 items-start gap-2 py-2">
                <span className="col-span-4 text-sm text-gray-600 pt-1">Adnotacje FA(3)</span>
                <div className="col-span-8 flex flex-wrap gap-3 text-sm">
                  {([
                    ['metodaKasowa',                  'Metoda kasowa (P_16)'],
                    ['samofakturowanie',              'Samofakturowanie (P_17)'],
                    ['odwrotneObciazenie',            'Odwrotne obciążenie (P_18)'],
                    ['mechanizmPodzielonejPlatnosci', 'Mechanizm podzielonej płatności (P_18A)'],
                  ] as [keyof Annotations, string][]).map(([key, label]) => (
                    <label key={key} className="flex items-center gap-1.5 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={annotations[key]}
                        onChange={e => setAnnotations(a => ({ ...a, [key]: e.target.checked }))}
                      />
                      {label}
                    </label>
                  ))}
                </div>
              </div>
            </div>
          </div>

          {/* ---- Seller ---- */}
          <div className="card overflow-hidden">
            <div className="px-5 py-3 border-b border-gray-100">
              <h3 className="font-medium text-gray-700 text-sm">Sprzedawca</h3>
            </div>
            <div className="px-5 py-2">
              <FieldRow
                label="Nazwa" required
                value={fields.sellerName}
                error={touched ? errors.sellerName : undefined}
                onChange={v => setTop('sellerName', v)}
              />
              <FieldRow
                label="NIP" required
                value={fields.sellerNip}
                error={touched ? errors.sellerNip : undefined}
                onChange={v => setTop('sellerNip', v)}
                placeholder="10 cyfr"
                mono
              />
              <FieldRow
                label="Adres" required
                value={fields.sellerAddress}
                error={touched ? errors.sellerAddress : undefined}
                onChange={v => setTop('sellerAddress', v)}
              />
            </div>
          </div>

          {/* ---- Buyer ---- */}
          <div className="card overflow-hidden">
            <div className="px-5 py-3 border-b border-gray-100">
              <h3 className="font-medium text-gray-700 text-sm">Nabywca</h3>
            </div>
            <div className="px-5 py-2">
              <FieldRow
                label="Nazwa" required
                value={fields.buyerName}
                error={touched ? errors.buyerName : undefined}
                onChange={v => setTop('buyerName', v)}
              />
              <FieldRow
                label="NIP" required
                value={fields.buyerNip}
                error={touched ? errors.buyerNip : undefined}
                onChange={v => setTop('buyerNip', v)}
                placeholder="10 cyfr"
                mono
              />
              <FieldRow
                label="Adres"
                value={fields.buyerAddress}
                error={touched ? errors.buyerAddress : undefined}
                onChange={v => setTop('buyerAddress', v)}
              />
            </div>
          </div>

          {/* ---- Items ---- */}
          <div className="card overflow-hidden">
            <div className="px-5 py-3 border-b border-gray-100">
              <h3 className="font-medium text-gray-700 text-sm">Pozycje faktury</h3>
            </div>

            {fields.items.map((item, idx) => {
              const ie = errors.items?.[idx] ?? {};
              return (
                <div
                  key={idx}
                  className="px-5 py-4 border-b border-gray-50 last:border-0 space-y-2"
                >
                  {/* Item header */}
                  <div className="flex items-center justify-between">
                    <span className="text-xs font-semibold text-gray-400 uppercase tracking-wide">
                      Pozycja {idx + 1}
                    </span>
                    {fields.items.length > 1 && (
                      <button
                        type="button"
                        className="text-xs text-red-400 hover:text-red-600 transition-colors"
                        onClick={() => removeItem(idx)}
                      >
                        Usuń pozycję
                      </button>
                    )}
                  </div>

                  {/* Name + unit */}
                  <div className="grid grid-cols-3 gap-2">
                    <div className="col-span-2 space-y-0.5">
                      <input
                        className={[
                          'input py-1 text-sm w-full',
                          touched && ie.name ? 'border-red-400 bg-red-50 focus:ring-red-200' : '',
                        ].join(' ')}
                        placeholder="Nazwa pozycji *"
                        value={item.name}
                        onChange={e => setItemField(idx, 'name', e.target.value)}
                      />
                      {touched && ie.name && (
                        <p className="text-xs text-red-500 flex items-center gap-1">
                          <AlertCircle size={11} className="shrink-0" />{ie.name}
                        </p>
                      )}
                    </div>
                    <div>
                      <input
                        className="input py-1 text-sm w-full"
                        placeholder="j.m. (np. szt.)"
                        value={item.unit}
                        onChange={e => setItemField(idx, 'unit', e.target.value)}
                      />
                    </div>
                  </div>

                  {/* Quantity + price + vat */}
                  <div className="grid grid-cols-3 gap-2">
                    {(
                      [
                        { key: 'quantity',     label: 'Ilość *' },
                        { key: 'netUnitPrice', label: 'Cena netto *' },
                        { key: 'vatRate',      label: 'Stawka VAT % *' },
                      ] as { key: keyof ItemFields; label: string }[]
                    ).map(({ key, label }) => (
                      <div key={key} className="space-y-0.5">
                        <input
                          className={[
                            'input py-1 text-sm w-full font-mono',
                            touched && ie[key] ? 'border-red-400 bg-red-50 focus:ring-red-200' : '',
                          ].join(' ')}
                          placeholder={label}
                          value={item[key]}
                          onChange={e => setItemField(idx, key, e.target.value)}
                        />
                        {touched && ie[key] && (
                          <p className="text-xs text-red-500 flex items-center gap-1">
                            <AlertCircle size={11} className="shrink-0" />{ie[key]}
                          </p>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              );
            })}

            <div className="px-5 py-3 bg-gray-50">
              <button
                type="button"
                className="btn-secondary text-xs py-1"
                onClick={addItem}
              >
                + Dodaj pozycję
              </button>
            </div>
          </div>

          {/* ---- Action bar ---- */}
          <div className="flex justify-end gap-3 pb-6">
            <Link href="/faktury" className="btn-secondary">Anuluj</Link>
            <button
              className="btn-primary"
              onClick={handleSend}
              disabled={loadingSend || loadingPreview}
            >
              <Send size={15} />
              {loadingSend ? 'Wysyłanie…' : 'Wyślij do KSeF'}
            </button>
          </div>
        </>
      )}
    </div>
  );
}
