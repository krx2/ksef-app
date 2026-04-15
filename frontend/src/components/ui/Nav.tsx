'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { FileText, Settings, Home, LogOut, AlertCircle, BarChart2 } from 'lucide-react';
import { useUser } from '@/lib/user-context';
import { cn } from '@/lib/utils';

const links = [
  { href: '/',             label: 'Strona główna', icon: Home },
  { href: '/faktury',      label: 'Faktury',       icon: FileText },
  { href: '/raporty',      label: 'Raporty',       icon: BarChart2 },
  { href: '/konfiguracja', label: 'Konfiguracja',  icon: Settings },
];

export default function Nav() {
  const pathname = usePathname();
  const { user, logout, newReceivedCount } = useUser();

  return (
    <nav className="bg-white border-b border-gray-200 sticky top-0 z-40">
      <div className="max-w-6xl mx-auto px-4 flex items-center justify-between h-14">
        <div className="flex items-center gap-1">
          <span className="font-semibold text-brand-600 mr-4 text-sm tracking-tight">
            KSeF Faktury
          </span>
          {user && links.map(({ href, label, icon: Icon }) => (
            <Link
              key={href}
              href={href}
              className={cn(
                'relative flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium transition-colors',
                pathname === href || (href !== '/' && pathname.startsWith(href))
                  ? 'bg-brand-50 text-brand-700'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100'
              )}
            >
              <Icon size={15} />
              {label}
              {href === '/' && newReceivedCount > 0 && (
                <span className="absolute -top-1 -right-1 flex items-center justify-center w-4 h-4 rounded-full bg-red-500 text-white text-[10px] font-bold leading-none">
                  <AlertCircle size={10} />
                </span>
              )}
            </Link>
          ))}
        </div>
        <div className="flex items-center gap-3 text-sm text-gray-500">
          {user && (
            <>
              <span className="font-medium text-gray-700">{user.companyName}</span>
              <span className="text-xs text-gray-400">NIP: {user.nip}</span>
              <button
                onClick={logout}
                className="flex items-center gap-1 px-2 py-1 rounded-md text-gray-500 hover:text-red-600 hover:bg-red-50 transition-colors"
                title="Wyloguj się"
              >
                <LogOut size={14} />
                <span className="text-xs">Wyloguj</span>
              </button>
            </>
          )}
        </div>
      </div>
    </nav>
  );
}
