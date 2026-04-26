/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  async rewrites() {
    // BACKEND_URL — adres backendu widoczny z serwera Next.js (nie przeglądarki).
    // W Dockerze zawsze http://backend:8080 (wewnętrzna sieć compose).
    // Lokalnie (bez Dockera): ustaw BACKEND_URL=http://localhost:8080 w .env.local
    const backendUrl = process.env.BACKEND_URL || 'http://backend:8080';
    return [
      {
        source: '/api/backend/:path*',
        destination: `${backendUrl}/api/:path*`,
      },
    ];
  },
};

module.exports = nextConfig;
