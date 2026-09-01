import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  metadataBase: new URL('http://localhost:4204'),
  title: 'GPS Signal Lab｜物流 GPS API 測試工具',
  description: '取得真實定位、用搖桿模擬移動，並直接測試物流司機 GPS API。',
  openGraph: {
    title: 'GPS Signal Lab',
    description: '真實定位・搖桿模擬・API 驗證',
    images: [{ url: '/og.png', width: 1200, height: 630, alt: 'GPS Signal Lab' }],
  },
  twitter: {
    card: 'summary_large_image',
    title: 'GPS Signal Lab',
    description: '真實定位・搖桿模擬・API 驗證',
    images: ['/og.png'],
  },
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="zh-Hant"><body>{children}</body></html>;
}
