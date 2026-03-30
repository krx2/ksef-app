'use client';

import { useState, useRef } from 'react';
import { useMutation } from '@tanstack/react-query';
import { X, TestTube } from 'lucide-react';
import { useUser } from '@/lib/user-context';
import { xlsxConfigsApi } from '@/lib/api';
import { INVOICE_FIELDS, type XlsxConfig, type FieldMapping } from '@/types';

interface Props {
  config: XlsxConfig | null;
  onClose: () => void;
  onSaved: () => void;
}

export default function XlsxConfigModal({ config, onClose, onSaved }: Props) {
  const { userId } = useUser();
  const fileRef = useRef<HTMLInputElement>(null);
  const [previewFile, setPreviewFile] = useState<File | null>(null);

  // Init form state from existing config or blanks
  const initMappings = () => {
    const m: Record<string, FieldMapping> = {};
    INVOICE_FIELDS.forEach(f => {
      m[f.key] = config?.fieldMappings?.[f.key] ?? { type: 'VALUE', value: '' };
    });
    return m;
  };

  const [name, setName] = useState(config?.name ?? '');
  const [description, setDescription] = useState(config?.description ?? '');
  const [mappings, setMappings] = useState<Record<string, FieldMapping>>(initMappings);
  const [testResults, setTestResults] = useState<Record<string, string>>({});
  const [testingField, setTestingField] = useState<string | null>(null);
  const [error, setError] = useState('');

  const mutation = useMutation({
    mutationFn: (data: unknown) =>
      config
        ? xlsxConfigsApi.update(userId, config.id, data)
        : xlsxConfigsApi.create(userId, data),
    onSuccess: onSaved,
    onError: (err: any) => setError(err?.response?.data?.error ?? 'Błąd zapisu'),
  });

  const setMapping = (key: string, patch: Partial<FieldMapping>) =>
    setMappings(prev => ({ ...prev, [key]: { ...prev[key], ...patch } as FieldMapping }));

  const testCell = async (fieldKey: string) => {
    const mapping = mappings[fieldKey];
    if (!previewFile || mapping.type !== 'CELL' || !mapping.cellRef) return;
    if (!userId) {
      setError('Nie można przetestować komórki — użytkownik nie jest zalogowany.');
      return;
    }
    setTestingField(fieldKey);
    try {
      const result = await xlsxConfigsApi.testCell(
        userId, previewFile, mapping.cellRef, mapping.sheetIndex ?? 0
      );
      setTestResults(prev => ({ ...prev, [fieldKey]: result.value }));
    } catch (err: any) {
      const msg = err?.response?.data?.error ?? 'błąd odczytu';
      setTestResults(prev => ({ ...prev, [fieldKey]: '⚠ ' + msg }));
    } finally {
      setTestingField(null);
    }
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    // Remove empty mappings
    const cleaned: Record<string, FieldMapping> = {};
    Object.entries(mappings).forEach(([k, v]) => {
      if (v.type === 'VALUE' && v.value) cleaned[k] = v;
      if (v.type === 'CELL' && v.cellRef) cleaned[k] = v;
    });
    mutation.mutate({ name, description, fieldMappings: cleaned });
  };

  return (
    <div
      className="fixed inset-0 bg-black/40 z-50 flex items-start justify-center overflow-y-auto py-10"
      onClick={e => e.target === e.currentTarget && onClose()}
    >
      <div className="bg-white rounded-xl shadow-xl w-full max-w-3xl mx-4">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <h2 className="text-lg font-semibold">
            {config ? 'Edytuj konfigurację XLSX' : 'Nowa konfiguracja XLSX'}
          </h2>
          <button onClick={onClose} className="text-gray-400 hover:text-gray-600">
            <X size={20} />
          </button>
        </div>

        <form onSubmit={handleSubmit}>
          <div className="px-6 py-5 space-y-5 max-h-[70vh] overflow-y-auto">
            {error && (
              <div className="bg-red-50 border border-red-200 rounded-lg p-3 text-sm text-red-700">
                {error}
              </div>
            )}

            {/* Name & description */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="label">Nazwa konfiguracji *</label>
                <input className="input" required value={name}
                  onChange={e => setName(e.target.value)}
                  placeholder="np. Szablon FV sprzedaż" />
              </div>
              <div>
                <label className="label">Opis (opcjonalnie)</label>
                <input className="input" value={description}
                  onChange={e => setDescription(e.target.value)} />
              </div>
            </div>

            {/* Test file upload */}
            <div className="bg-blue-50 border border-blue-100 rounded-lg p-4 space-y-2">
              <p className="text-sm font-medium text-blue-800">
                Testowy plik XLSX (opcjonalnie)
              </p>
              <p className="text-xs text-blue-600">
                Wgraj przykładowy plik, aby testować odczyt komórek w czasie konfiguracji.
              </p>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  className="btn-secondary py-1 text-xs"
                  onClick={() => fileRef.current?.click()}
                >
                  {previewFile ? previewFile.name : 'Wybierz plik…'}
                </button>
                {previewFile && (
                  <span className="text-xs text-green-600">✓ gotowy do testowania</span>
                )}
              </div>
              <input
                ref={fileRef}
                type="file"
                accept=".xlsx,.xls"
                className="hidden"
                onChange={e => setPreviewFile(e.target.files?.[0] ?? null)}
              />
            </div>

            {/* Field mappings */}
            <div className="space-y-1">
              <p className="text-sm font-medium text-gray-700 mb-3">
                Mapowanie pól — dla każdego pola wybierz:
                stałą wartość <strong>lub</strong> adres komórki w Excelu (np. A1, B5)
              </p>

              <div className="divide-y divide-gray-100 border border-gray-200 rounded-lg overflow-hidden">
                {INVOICE_FIELDS.map(field => {
                  const mapping = mappings[field.key];
                  const isCell = mapping.type === 'CELL';
                  const testResult = testResults[field.key];

                  return (
                    <div key={field.key} className="grid grid-cols-12 items-center gap-2 px-4 py-3 text-sm">
                      {/* Field label */}
                      <div className="col-span-3">
                        <span className="text-gray-700">{field.label}</span>
                        {field.required && <span className="text-red-500 ml-0.5">*</span>}
                      </div>

                      {/* Type toggle */}
                      <div className="col-span-2 flex">
                        <button
                          type="button"
                          onClick={() => setMapping(field.key, { type: 'VALUE' })}
                          className={`px-2 py-1 text-xs border rounded-l-md transition-colors ${
                            !isCell
                              ? 'bg-brand-600 text-white border-brand-600'
                              : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'
                          }`}
                        >
                          Stała
                        </button>
                        <button
                          type="button"
                          onClick={() => setMapping(field.key, { type: 'CELL' })}
                          className={`px-2 py-1 text-xs border-t border-b border-r rounded-r-md transition-colors ${
                            isCell
                              ? 'bg-brand-600 text-white border-brand-600'
                              : 'bg-white text-gray-600 border-gray-300 hover:bg-gray-50'
                          }`}
                        >
                          Komórka
                        </button>
                      </div>

                      {/* Value / cell ref input */}
                      {isCell ? (
                        <>
                          <div className="col-span-2">
                            <input
                              className="input py-1 text-xs uppercase font-mono"
                              placeholder="np. A1"
                              value={mapping.cellRef ?? ''}
                              onChange={e => setMapping(field.key, { cellRef: e.target.value.toUpperCase() })}
                            />
                          </div>
                          <div className="col-span-1">
                            <input
                              className="input py-1 text-xs text-center"
                              placeholder="Ark."
                              type="number"
                              min={0}
                              value={mapping.sheetIndex ?? 0}
                              onChange={e => setMapping(field.key, { sheetIndex: parseInt(e.target.value) || 0 })}
                            />
                          </div>
                          <div className="col-span-2">
                            <button
                              type="button"
                              className="btn-secondary py-1 text-xs w-full"
                              disabled={!previewFile || !mapping.cellRef || testingField === field.key || !userId}
                              onClick={() => testCell(field.key)}
                            >
                              <TestTube size={11} />
                              {testingField === field.key ? '…' : 'Testuj'}
                            </button>
                          </div>
                          <div className="col-span-2 text-xs">
                            {testResult !== undefined ? (
                              <span className={testResult.startsWith('⚠') ? 'text-red-500' : 'text-green-600 font-medium'}>
                                {testResult || '(puste)'}
                              </span>
                            ) : (
                              <span className="text-gray-300">—</span>
                            )}
                          </div>
                        </>
                      ) : (
                        <div className="col-span-7">
                          <input
                            className="input py-1 text-xs"
                            placeholder="Wpisz stałą wartość…"
                            value={mapping.value ?? ''}
                            onChange={e => setMapping(field.key, { value: e.target.value })}
                          />
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          </div>

          {/* Footer */}
          <div className="px-6 py-4 border-t border-gray-100 flex justify-end gap-2">
            <button type="button" className="btn-secondary" onClick={onClose}>
              Anuluj
            </button>
            <button type="submit" className="btn-primary" disabled={mutation.isPending}>
              {mutation.isPending ? 'Zapisywanie…' : 'Zapisz konfigurację'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
