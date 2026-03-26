'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { Plus, Trash2, Send } from 'lucide-react';
import { useUser } from '@/lib/user-context';
import { invoicesApi } from '@/lib/api';

interface ItemRow {
  name: string;
  unit: string;
  quantity: string;
  netUnitPrice: string;
  vatRate: string;
}

const emptyItem = (): ItemRow => ({
  name: '', unit: 'szt.', quantity: '1', netUnitPrice: '', vatRate: '23',
});

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
    sellerName: user?.companyName ?? '',
    sellerNip: user?.nip ?? '',
    sellerAddress: '',
    buyerName: '',
    buyerNip: '',
    buyerAddress: '',
    currency: 'PLN',
  });

  const [items, setItems] = useState<ItemRow[]>([emptyItem()]);

  const setField = (k: keyof typeof fields, v: string) =>
    setFields(f => ({ ...f, [k]: v }));

  const setItem = (i: number, k: keyof ItemRow, v: string) =>
    setItems(rows => rows.map((r, idx) => idx === i ? { ...r, [k]: v } : r));

  const addItem = () => setItems(r => [...r, emptyItem()]);
  const removeItem = (i: number) => setItems(r => r.filter((_, idx) => idx !== i));

  const calcGross = (item: ItemRow) => {
    const net = parseFloat(item.netUnitPrice || '0') * parseFloat(item.quantity || '0');
    const vat = net * parseFloat(item.vatRate || '0') / 100;
    return isNaN(net + vat) ? '—' : (net + vat).toFixed(2);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');
    try {
      await invoicesApi.createFromForm(userId, {
        ...fields,
        items: items.map(it => ({
          name: it.name,
          unit: it.unit || undefined,
          quantity: parseFloat(it.quantity),
          netUnitPrice: parseFloat(it.netUnitPrice),
          vatRate: parseFloat(it.vatRate),
        })),
      });
      router.push('/faktury');
    } catch (err: any) {
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
        <div className="bg-red-50 border border-red-200 rounded-lg p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {/* Header fields */}
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
        </div>
      </div>

      {/* Seller / Buyer */}
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
              value={fields.sellerNip}
              onChange={e => setField('sellerNip', e.target.value)} />
          </div>
          <div>
            <label className="label">Adres</label>
            <input className="input" value={fields.sellerAddress}
              onChange={e => setField('sellerAddress', e.target.value)} />
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
              value={fields.buyerNip}
              onChange={e => setField('buyerNip', e.target.value)} />
          </div>
          <div>
            <label className="label">Adres</label>
            <input className="input" value={fields.buyerAddress}
              onChange={e => setField('buyerAddress', e.target.value)} />
          </div>
        </div>
      </div>

      {/* Line items */}
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
              <th className="px-3 py-2 w-20">VAT %</th>
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
                  <select className="input py-1" value={item.vatRate}
                    onChange={e => setItem(i, 'vatRate', e.target.value)}>
                    <option>23</option>
                    <option>8</option>
                    <option>5</option>
                    <option>0</option>
                  </select>
                </td>
                <td className="px-3 py-2 text-right font-medium text-gray-700">
                  {calcGross(item)} PLN
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
