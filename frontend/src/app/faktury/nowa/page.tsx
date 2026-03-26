'use client';

import { Suspense } from 'react';
import { useSearchParams } from 'next/navigation';
import InvoiceFormPage from '@/components/forms/InvoiceFormPage';
import XlsxUploadPage from '@/components/forms/XlsxUploadPage';

function NowaFakturaInner() {
  const params = useSearchParams();
  const source = params.get('source');
  return source === 'xlsx' ? <XlsxUploadPage /> : <InvoiceFormPage />;
}

export default function NowaFaktura() {
  return (
    <Suspense>
      <NowaFakturaInner />
    </Suspense>
  );
}
