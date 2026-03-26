'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { FileText, Settings, LayoutDashboard, User } from 'lucide-react';
import { useUser } from '@/lib/user-context';
import { cn } from '@/lib/utils';

const links = [
  { href: '/',              label: 'Dashboard',    icon: LayoutDashboard },
  { href: '/faktury',       label: 'Faktury',      icon: FileText },
  { href: '/konfiguracja',  label: 'Konfiguracja', icon: Settings },
];

export default function Nav() {
  const pathname = usePathname();
  const { user } = useUser();

  return (
    <nav className="bg-white border-b border-gray-200 sticky top-0 z-40">
      <div className="max-w-6xl mx-auto px-4 flex items-center justify-between h-14">
        <div className="flex items-center gap-1">
          <span className="font-semibold text-brand-600 mr-4 text-sm tracking-tight">
            KSeF Faktury
          </span>
          {links.map(({ href, label, icon: Icon }) => (
            <Link
              key={href}
              href={href}
              className={cn(
                'flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium transition-colors',
                pathname === href || (href !== '/' && pathname.startsWith(href))
                  ? 'bg-brand-50 text-brand-700'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-100'
              )}
            >
              <Icon size={15} />
              {label}
            </Link>
          ))}
        </div>
        <div className="flex items-center gap-2 text-sm text-gray-500">
          <User size={15} />
          <span>{user?.companyName ?? 'Nie zalogowano'}</span>
          {user && (
            <span className="text-xs text-gray-400">NIP: {user.nip}</span>
          )}
        </div>
      </div>
    </nav>
  );
}
