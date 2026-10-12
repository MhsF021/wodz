import { useState } from 'react';
import { Trophy } from 'lucide-react';
import { API_BASE } from '../api/client';

export function EventImage({ src, name, profile = false, cover = false }: { src?: string | null; name: string; profile?: boolean; cover?: boolean }) {
  const [failed, setFailed] = useState<string | null>(null);
  const url = src?.startsWith('/api/public/championship-images/') ? `${API_BASE}${src}`
    : src?.startsWith('https://') || /^data:image\/(jpeg|png);base64,/.test(src ?? '') ? src : null;
  return <div className={`event-image${profile ? ' event-image--profile' : ''}${cover ? ' event-image--cover' : ''}`}>
    {url && failed !== url
      ? <img src={url} alt={cover ? '' : `Imagem de ${name}`} loading={profile || cover ? 'eager' : 'lazy'} decoding="async"
          referrerPolicy="no-referrer" onError={() => setFailed(url)} />
      : <Trophy size={profile ? 32 : 42} aria-hidden />}
  </div>;
}
