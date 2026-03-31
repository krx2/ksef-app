'use client';

import { useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { Plus, Trash2, Send, AlertCircle } from 'lucide-react';
import { useUser } from '@/lib/user-context';
import { invoicesApi } from '@/lib/api';
import { RodzajFaktury, RODZAJ_FAKTURY_LABELS, VAT_RATE_CODE_LABELS, VatRateCode } from '@/types';

interface ItemRow {
  name: string;
  unit: string;
  quantity: string;
  netUnitPrice: string;
  vatRateCode: VatRateCode;
}

const emptyItem = (): ItemRow => ({
  name: '', unit: 'szt.', quantity: '1', netUnitPrice: '', vatRateCode: '23',
});

/** Kody stawek numerycznych z wartością vatRate do obliczeń */
const NUMERIC_RATE_MAP: Record<VatRateCode, number> = {
  '23': 23, '22': 22, '8': 8, '7': 7, '5': 5, '4': 4, '3': 3,
  '0 KR': 0, '0 WDT': 0, '0 EX': 0, 'zw': 0, 'oo': 0, 'np I': 0, 'np II': 0,
};

const COMMON_CURRENCIES = ['PLN', 'EUR', 'USD', 'GBP', 'CHF', 'CZK', 'NOK', 'SEK', 'DKK', 'HUF'];

const NIP_RE = /^\d{10}$/;

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

interface FormErrors {
  invoiceNumber?: string;
  sellerNip?: string;
  sellerAddress?: string;
  buyerNip?: string;
  items?: { name?: string; quantity?: string; netUnitPrice?: string }[];
}

function validateForm(
  fields: ReturnType<typeof buildEmptyFields>,
  items: ItemRow[]
): FormErrors {
  const e: FormErrors = {};

  if (!fields.invoiceNumber.trim())
    e.invoiceNumber = 'Numer faktury jest wymagany';

  if (!NIP_RE.test(fields.sellerNip.replace(/[- ]/g, '')))
    e.sellerNip = 'NIP sprzedawcy: dokładnie 10 cyfr';

  if (!fields.sellerAddress.trim())
    e.sellerAddress = 'Adres sprzedawcy jest wymagany w FA(3)';

  if (!NIP_RE.test(fields.buyerNip.replace(/[- ]/g, '')))
    e.buyerNip = 'NIP nabywcy: dokładnie 10 cyfr';

  const itemErrors = items.map(it => {
    const ie: { name?: string; quantity?: string; netUnitPrice?: string } = {};
    if (!it.name.trim()) ie.name = 'Nazwa pozycji jest wymagana';
    const qty = parseFloat(it.quantity.replace(',', '.'));
    if (isNaN(qty) || qty <= 0) ie.quantity = 'Ilość musi być liczbą większą od 0';
    const price = parseFloat(it.netUnitPrice.replace(',', '.'));
    if (isNaN(price) || price <= 0) ie.netUnitPrice = 'Cena netto musi być liczbą większą od 0';
    return ie;
  });

  if (itemErrors.some(ie => Object.keys(ie).length > 0))
    e.items = itemErrors;

  return e;
}

function countErrors(e: FormErrors): number {
  const { items, ...top } = e;
  return Object.keys(top).length +
    (items?.reduce((s, ie) => s + Object.keys(ie).length, 0) ?? 0);
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function buildEmptyFields(user?: { companyName?: string; nip?: string }) {
  const today = new Date().toISOString().slice(0, 10);
  return {
    invoiceNumber: '',
    issueDate: today,
    saleDate: today,
    rodzajFaktury: 'VAT' as RodzajFaktury,
    sellerName: user?.companyName ?? '',
    sellerNip: user?.nip ?? '',
    sellerAddress: '',
    sellerCountryCode: 'PL',
    buyerName: '',
    buyerNip: '',
    buyerAddress: '',
    buyerCountryCode: 'PL',
    currency: 'PLN',
    metodaKasowa: false,
    samofakturowanie: false,
    odwrotneObciazenie: false,
    mechanizmPodzielonejPlatnosci: false,
    zwolnieniePodatkowe: '',
    jst: false,
    gv: false,
  };
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function InvoiceFormPage() {
  const router = useRouter();
  const { userId, user } = useUser();
  const [loading, setLoading] = useState(false);
  const [apiError, setApiError] = useState('');
  const [formErrors, setFormErrors] = useState<FormErrors>({});
  const [touched, setTouched] = useState(false);

  const [fields, setFields] = useState(() => buildEmptyFields(user ?? undefined));
  const [items, setItems] = useState<ItemRow[]>([emptyItem()]);

  // Ostrzeżenie przy próbie opuszczenia strony z wypełnionym formularzem
  useEffect(() => {
    const isDirty =
      fields.invoiceNumber !== '' ||
      fields.sellerAddress !== '' ||
      fields.buyerName !== '' ||
      items.some(it => it.name !== '' || it.netUnitPrice !== '');
    if (!isDirty) return;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [fields, items]);

  const setField = <K extends keyof typeof fields>(k: K, v: typeof fields[K]) =>
    setFields(f => ({ ...f, [k]: v }));

  const setItem = (i: number, k: keyof ItemRow, v: string) =>
    setItems(rows => rows.map((r, idx) => idx === i ? { ...r, [k]: v } : r));

  const addItem = () => setItems(r => [...r, emptyItem()]);
  const removeItem = (i: number) => setItems(r => r.filter((_, idx) => idx !== i));

  const calcGross = (item: ItemRow) => {
    const net =
      parseFloat((item.netUnitPrice || '0').replace(',', '.')) *
      parseFloat((item.quantity || '0').replace(',', '.'));
    const rate = NUMERIC_RATE_MAP[item.vatRateCode] ?? 0;
    const vat = net * rate / 100;
    return isNaN(net + vat) ? '—' : (net + vat).toFixed(2);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setTouched(true);

    const errors = validateForm(fields, items);
    setFormErrors(errors);
    if (countErrors(errors) > 0) return;

    setLoading(true);
    setApiError('');
    try {
      await invoicesApi.createFromForm(userId, {
        ...fields,
        // Wyślij zwolnieniePodatkowe tylko gdy jest niepuste
        zwolnieniePodatkowe: fields.zwolnieniePodatkowe.trim() || undefined,
        items: items.map(it => ({
          name: it.name,
          unit: it.unit || undefined,
          quantity: parseFloat(it.quantity.replace(',', '.')),
          netUnitPrice: parseFloat(it.netUnitPrice.replace(',', '.')),
          vatRate: NUMERIC_RATE_MAP[it.vatRateCode],
          vatRateCode: it.vatRateCode,
        })),
      });
      router.push('/faktury');
    } catch (err: any) {
      setApiError(
        err?.response?.data?.error ??
        err?.message ??
        'Błąd podczas wysyłania faktury'
      );
    } finally {
      setLoading(false);
    }
  };

  const errCount = touched ? countErrors(formErrors) : 0;

  return (
    <form onSubmit={handleSubmit} className="space-y-6 max-w-3xl">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Nowa faktura</h1>
        <button type="submit" className="btn-primary" disabled={loading}>
          <Send size={15} />
          {loading ? 'Wysyłanie…' : 'Wyślij do KSeF'}
        </button>
      </div>

      {/* Błąd serwera */}
      {apiError && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-3 text-sm text-red-700 whitespace-pre-wrap flex items-start gap-2">
          <AlertCircle size={15} className="shrink-0 mt-0.5" />
          {apiError}
        </div>
      )}

      {/* Podsumowanie błędów walidacji */}
      {touched && errCount > 0 && (
        <div className="bg-amber-50 border border-amber-200 rounded-lg px-4 py-3 flex items-center gap-2 text-sm text-amber-800">
          <AlertCircle size={15} className="shrink-0 text-amber-500" />
          Znaleziono <strong className="mx-1">{errCount}</strong>
          {errCount === 1 ? 'błąd' : 'błędy / błędów'} — popraw podświetlone pola przed wysłaniem.
        </div>
      )}

      {/* Dane faktury */}
      <div className="card p-5 space-y-4">
        <h2 className="font-medium text-gray-700">Dane faktury</h2>
        <div className="grid grid-cols-3 gap-4">
          <div>
            <label className="label">Numer faktury *</label>
            <input
              className={`input ${touched && formErrors.invoiceNumber ? 'border-red-400 bg-red-50' : ''}`}
              required
              value={fields.invoiceNumber}
              onChange={e => setField('invoiceNumber', e.target.value)}
            />
            {touched && formErrors.invoiceNumber && (
              <p className="text-xs text-red-500 mt-0.5 flex items-center gap-1">
                <AlertCircle size={11} />{formErrors.invoiceNumber}
              </p>
            )}
          </div>
          <div>
            <label className="label">Data wystawienia *</label>
            <input className="input" type="date" required value={fields.issueDate}
              onChange={e => setField('issueDate', e.target.value)} />
          </div>
          <div>
            <label className="label">Data sprzedaży</label>
            <input className="input" type="date" value={fields.saleDate}
              onChange={e => setField('saleDate', e.target.value)} />
          </div>
          <div>
            <label className="label">Rodzaj faktury *</label>
            <select className="input" value={fields.rodzajFaktury}
              onChange={e => setField('rodzajFaktury', e.target.value as RodzajFaktury)}>
              {(Object.keys(RODZAJ_FAKTURY_LABELS) as RodzajFaktury[]).map(k => (
                <option key={k} value={k}>{RODZAJ_FAKTURY_LABELS[k]}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="label">Waluta</label>
            <select className="input" value={fields.currency}
              onChange={e => setField('currency', e.target.value)}>
              {COMMON_CURRENCIES.map(c => (
                <option key={c} value={c}>{c}</option>
              ))}
            </select>
          </div>
        </div>

        {/* Adnotacje FA(3) */}
        <div>
          <p className="label mb-1">Adnotacje FA(3)</p>
          <div className="flex flex-wrap gap-4 text-sm">
            {([
              ['metodaKasowa',              'Metoda kasowa (P_16)'],
              ['samofakturowanie',          'Samofakturowanie (P_17)'],
              ['odwrotneObciazenie',        'Odwrotne obciążenie (P_18)'],
              ['mechanizmPodzielonejPlatnosci', 'Mechanizm podzielonej płatności (P_18A)'],
              ['jst',                       'Jednostka podrzędna JST'],
              ['gv',                        'Członek grupy VAT (GV)'],
            ] as [keyof typeof fields, string][]).map(([key, label]) => (
              <label key={key} className="flex items-center gap-1.5 cursor-pointer">
                <input type="checkbox"
                  checked={fields[key] as boolean}
                  onChange={e => setField(key, e.target.checked as any)} />
                {label}
              </label>
            ))}
          </div>
        </div>

        {/* Podstawa zwolnienia z VAT — widoczna gdy co najmniej jedna pozycja ma kod "zw" */}
        {items.some(it => it.vatRateCode === 'zw') && (
          <div>
            <label className="label">
              Podstawa zwolnienia z VAT (P_19) *
              <span className="ml-1 text-xs text-gray-400 font-normal">
                — wymagana gdy pozycja ma stawkę&nbsp;<em>zw</em>
              </span>
            </label>
            <input
              className="input"
              placeholder='np. "art. 43 ust. 1 pkt 1 ustawy"'
              value={fields.zwolnieniePodatkowe}
              onChange={e => setField('zwolnieniePodatkowe', e.target.value)}
            />
          </div>
        )}
      </div>

      {/* Sprzedawca / Nabywca */}
      <div className="grid grid-cols-2 gap-4">
        <div className="card p-5 space-y-3">
          <h2 className="font-medium text-gray-700">Sprzedawca</h2>
          <div>
            <label className="label">Nazwa *</label>
            <input className="input" required value={fields.sellerName}
              onChange={e => setField('sellerName', e.target.value)} />
          </div>
          <div>
            <label className="label">NIP *</label>
            <input
              className={`input ${touched && formErrors.sellerNip ? 'border-red-400 bg-red-50' : ''}`}
              required maxLength={10} pattern="\d{10}" title="NIP: 10 cyfr"
              value={fields.sellerNip}
              onChange={e => setField('sellerNip', e.target.value)}
            />
            {touched && formErrors.sellerNip && (
              <p className="text-xs text-red-500 mt-0.5 flex items-center gap-1">
                <AlertCircle size={11} />{formErrors.sellerNip}
              </p>
            )}
          </div>
          <div>
            <label className="label">Adres *</label>
            <input
              className={`input ${touched && formErrors.sellerAddress ? 'border-red-400 bg-red-50' : ''}`}
              required value={fields.sellerAddress}
              onChange={e => setField('sellerAddress', e.target.value)}
            />
            {touched && formErrors.sellerAddress && (
              <p className="text-xs text-red-500 mt-0.5 flex items-center gap-1">
                <AlertCircle size={11} />{formErrors.sellerAddress}
              </p>
            )}
          </div>
          <div>
            <label className="label">Kod kraju</label>
            <input className="input" maxLength={2} value={fields.sellerCountryCode}
              onChange={e => setField('sellerCountryCode', e.target.value.toUpperCase())} />
          </div>
        </div>
        <div className="card p-5 space-y-3">
          <h2 className="font-medium text-gray-700">Nabywca</h2>
          <div>
            <label className="label">Nazwa *</label>
            <input className="input" required value={fields.buyerName}
              onChange={e => setField('buyerName', e.target.value)} />
          </div>
          <div>
            <label className="label">NIP *</label>
            <input
              className={`input ${touched && formErrors.buyerNip ? 'border-red-400 bg-red-50' : ''}`}
              required maxLength={10} pattern="\d{10}" title="NIP: 10 cyfr"
              value={fields.buyerNip}
              onChange={e => setField('buyerNip', e.target.value)}
            />
            {touched && formErrors.buyerNip && (
              <p className="text-xs text-red-500 mt-0.5 flex items-center gap-1">
                <AlertCircle size={11} />{formErrors.buyerNip}
              </p>
            )}
          </div>
          <div>
            <label className="label">Adres</label>
            <input className="input" value={fields.buyerAddress}
              onChange={e => setField('buyerAddress', e.target.value)} />
          </div>
          <div>
            <label className="label">Kod kraju</label>
            <input className="input" maxLength={2} value={fields.buyerCountryCode}
              onChange={e => setField('buyerCountryCode', e.target.value.toUpperCase())} />
          </div>
        </div>
      </div>

      {/* Pozycje faktury */}
      <div className="card overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-100 flex items-center justify-between">
          <h2 className="font-medium text-gray-700">Pozycje faktury</h2>
          <button type="button" onClick={addItem} className="btn-secondary py-1 text-xs">
            <Plus size={13} /> Dodaj pozycję
          </button>
        </div>
        <table className="w-full text-sm">
          <thead className="bg-gray-50 text-gray-500 text-xs">
            <tr>
              <th className="px-4 py-2 text-left">Nazwa</th>
              <th className="px-3 py-2 w-16">J.m.</th>
              <th className="px-3 py-2 w-20">Ilość</th>
              <th className="px-3 py-2 w-28">Cena netto</th>
              <th className="px-3 py-2 w-32">Stawka VAT</th>
              <th className="px-3 py-2 w-24 text-right">Brutto</th>
              <th className="px-3 py-2 w-8"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {items.map((item, i) => {
              const ie = formErrors.items?.[i] ?? {};
              return (
                <tr key={i}>
                  <td className="px-4 py-2">
                    <input
                      className={`input py-1 ${touched && ie.name ? 'border-red-400 bg-red-50' : ''}`}
                      required value={item.name}
                      onChange={e => setItem(i, 'name', e.target.value)}
                    />
                    {touched && ie.name && (
                      <p className="text-xs text-red-500 flex items-center gap-1 mt-0.5">
                        <AlertCircle size={11} />{ie.name}
                      </p>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    <input className="input py-1" value={item.unit}
                      onChange={e => setItem(i, 'unit', e.target.value)} />
                  </td>
                  <td className="px-3 py-2">
                    <input
                      className={`input py-1 text-right ${touched && ie.quantity ? 'border-red-400 bg-red-50' : ''}`}
                      type="number" min="0.0001" step="any" required
                      value={item.quantity}
                      onChange={e => setItem(i, 'quantity', e.target.value)}
                    />
                    {touched && ie.quantity && (
                      <p className="text-xs text-red-500 flex items-center gap-1 mt-0.5">
                        <AlertCircle size={11} />{ie.quantity}
                      </p>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    <input
                      className={`input py-1 text-right ${touched && ie.netUnitPrice ? 'border-red-400 bg-red-50' : ''}`}
                      type="number" min="0" step="0.01" required
                      value={item.netUnitPrice}
                      onChange={e => setItem(i, 'netUnitPrice', e.target.value)}
                    />
                    {touched && ie.netUnitPrice && (
                      <p className="text-xs text-red-500 flex items-center gap-1 mt-0.5">
                        <AlertCircle size={11} />{ie.netUnitPrice}
                      </p>
                    )}
                  </td>
                  <td className="px-3 py-2">
                    <select className="input py-1" value={item.vatRateCode}
                      onChange={e => setItem(i, 'vatRateCode', e.target.value)}>
                      {(Object.keys(VAT_RATE_CODE_LABELS) as VatRateCode[]).map(k => (
                        <option key={k} value={k}>{VAT_RATE_CODE_LABELS[k]}</option>
                      ))}
                    </select>
                  </td>
                  <td className="px-3 py-2 text-right font-medium text-gray-700">
                    {calcGross(item)} {fields.currency}
                  </td>
                  <td className="px-3 py-2">
                    {items.length > 1 && (
                      <button type="button" onClick={() => removeItem(i)}
                        className="text-gray-400 hover:text-red-500 transition-colors">
                        <Trash2 size={14} />
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </form>
  );
}
