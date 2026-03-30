'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { Plus, Trash2, Send } from 'lucide-react';
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

export default function InvoiceFormPage() {
  const router = useRouter();
  const { userId, user } = useUser();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const today = new Date().toISOString().slice(0, 10);

  const [fields, setFields] = useState({
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
  });

  const [items, setItems] = useState<ItemRow[]>([emptyItem()]);

  const setField = <K extends keyof typeof fields>(k: K, v: typeof fields[K]) =>
    setFields(f => ({ ...f, [k]: v }));

  const setItem = (i: number, k: keyof ItemRow, v: string) =>
    setItems(rows => rows.map((r, idx) => idx === i ? { ...r, [k]: v } : r));

  const addItem = () => setItems(r => [...r, emptyItem()]);
  const removeItem = (i: number) => setItems(r => r.filter((_, idx) => idx !== i));

  const calcGross = (item: ItemRow) => {
    // TODO: parseFloat() używa separatora dziesiętnego "." (angielski).
    //       Użytkownik wpisujący "100,50" (polska lokalizacja) otrzyma net=100 zamiast 100.5.
    //       Naprawić przez zamianę przecinka: parseFloat(item.netUnitPrice.replace(',', '.') || '0').
    //       To samo dotyczy pola quantity. Analogiczny fix jest już zaimplementowany w XlsxUploadPage.tsx.
    const net = parseFloat(item.netUnitPrice || '0') * parseFloat(item.quantity || '0');
    const rate = NUMERIC_RATE_MAP[item.vatRateCode] ?? 0;
    const vat = net * rate / 100;
    return isNaN(net + vat) ? '—' : (net + vat).toFixed(2);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');
    // TODO: Formularz nie przeprowadza żadnej walidacji po stronie klienta przed wysłaniem.
    //       Wszystkie błędy (pusty NIP, brak adresu, nieprawidłowa waluta) są odkrywane
    //       dopiero po odpowiedzi backendu (HTTP 400). Warto dodać funkcję validateFields()
    //       analogiczną do tej w XlsxUploadPage.tsx — wyświetlać błędy inline zanim
    //       w ogóle zostanie wykonane żądanie HTTP.
    // TODO: Brak ostrzeżenia przy próbie opuszczenia strony z niezapisanymi zmianami.
    //       Dodać: useEffect(() => { const handler = (e) => { e.preventDefault(); e.returnValue = ''; };
    //              window.addEventListener('beforeunload', handler); return () => window.removeEventListener(...); }, [fields, items]);
    try {
      await invoicesApi.createFromForm(userId, {
        ...fields,
        items: items.map(it => ({
          name: it.name,
          unit: it.unit || undefined,
          quantity: parseFloat(it.quantity),
          netUnitPrice: parseFloat(it.netUnitPrice),
          vatRate: NUMERIC_RATE_MAP[it.vatRateCode],
          vatRateCode: it.vatRateCode,
        })),
      });
      router.push('/faktury');
    } catch (err: any) {
      // TODO: Brak przycisku "Spróbuj ponownie" przy błędzie sieciowym (timeout, 503).
      //       Użytkownik musi ponownie wypełnić formularz. Można zachować dane formularza
      //       w localStorage jako autosave i przywrócić je po odświeżeniu strony.
      setError(err?.response?.data?.error ?? 'Błąd podczas wysyłania faktury');
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-6 max-w-3xl">
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Nowa faktura</h1>
        <button type="submit" className="btn-primary" disabled={loading}>
          <Send size={15} />
          {loading ? 'Wysyłanie…' : 'Wyślij do KSeF'}
        </button>
      </div>

      {error && (
        <div className="bg-red-50 border border-red-200 rounded-lg p-3 text-sm text-red-700 whitespace-pre-wrap">
          {error}
        </div>
      )}

      {/* Dane faktury */}
      <div className="card p-5 space-y-4">
        <h2 className="font-medium text-gray-700">Dane faktury</h2>
        <div className="grid grid-cols-3 gap-4">
          <div>
            <label className="label">Numer faktury *</label>
            <input className="input" required value={fields.invoiceNumber}
              onChange={e => setField('invoiceNumber', e.target.value)} />
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
            {/* TODO: Pole waluta akceptuje dowolny 3-literowy ciąg (np. "XYZ").
                      Backend odrzuci nieprawidłowy kod przez Fa3Validator, ale frontend
                      nie daje żadnej wskazówki jakie kody są poprawne.
                      Zmienić na <select> z listą walut ISO 4217 (przynajmniej najpopularniejszych:
                      PLN, EUR, USD, GBP, CHF, CZK, NOK, SEK) lub dodać walidację inline. */}
            <input className="input" maxLength={3} value={fields.currency}
              onChange={e => setField('currency', e.target.value.toUpperCase())} />
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
            <input className="input" required maxLength={10} pattern="\d{10}"
              title="NIP: 10 cyfr"
              value={fields.sellerNip}
              onChange={e => setField('sellerNip', e.target.value)} />
          </div>
          <div>
            <label className="label">Adres *</label>
            <input className="input" required value={fields.sellerAddress}
              onChange={e => setField('sellerAddress', e.target.value)} />
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
            <input className="input" required maxLength={10} pattern="\d{10}"
              title="NIP: 10 cyfr"
              value={fields.buyerNip}
              onChange={e => setField('buyerNip', e.target.value)} />
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
              <th className="px-3 py-2 w-16">Ilość</th>
              <th className="px-3 py-2 w-28">Cena netto</th>
              <th className="px-3 py-2 w-32">Stawka VAT</th>
              <th className="px-3 py-2 w-24 text-right">Brutto</th>
              <th className="px-3 py-2 w-8"></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {items.map((item, i) => (
              <tr key={i}>
                <td className="px-4 py-2">
                  <input className="input py-1" required value={item.name}
                    onChange={e => setItem(i, 'name', e.target.value)} />
                </td>
                <td className="px-3 py-2">
                  <input className="input py-1" value={item.unit}
                    onChange={e => setItem(i, 'unit', e.target.value)} />
                </td>
                <td className="px-3 py-2">
                  <input className="input py-1 text-right" type="number" min="0.0001" step="any"
                    required value={item.quantity}
                    onChange={e => setItem(i, 'quantity', e.target.value)} />
                </td>
                <td className="px-3 py-2">
                  <input className="input py-1 text-right" type="number" min="0" step="0.01"
                    required value={item.netUnitPrice}
                    onChange={e => setItem(i, 'netUnitPrice', e.target.value)} />
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
            ))}
          </tbody>
        </table>
      </div>
    </form>
  );
}
