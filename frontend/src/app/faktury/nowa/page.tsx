'use client';

import { useEffect, Suspense } from 'react';
import { useSearchParams, useRouter } from 'next/navigation';
import { useUser } from '@/lib/user-context';
import InvoiceFormPage from '@/components/forms/InvoiceFormPage';
import XlsxUploadPage from '@/components/forms/XlsxUploadPage';

function NowaFakturaInner() {
  const { user, isLoaded } = useUser();
  const router = useRouter();
  const params = useSearchParams();

  useEffect(() => {
    if (isLoaded && !user) router.replace('/');
  }, [isLoaded, user, router]);

  if (!isLoaded || !user) return null;

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
