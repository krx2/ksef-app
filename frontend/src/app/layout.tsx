import type { Metadata } from 'next';
import './globals.css';
import { UserProvider } from '@/lib/user-context';
import Nav from '@/components/ui/Nav';
import QueryProvider from '@/components/ui/QueryProvider';

export const metadata: Metadata = {
  title: 'KSeF — Faktury',
  description: 'System zarządzania fakturami KSeF',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="pl">
      <body className="bg-gray-50 min-h-screen font-sans antialiased">
        <QueryProvider>
          <UserProvider>
            <Nav />
            <main className="max-w-6xl mx-auto px-4 py-8">{children}</main>
          </UserProvider>
        </QueryProvider>
      </body>
    </html>
  );
}
