import { useEffect, useRef, useState, type PointerEvent } from 'react';
import { ImagePlus, Move, RotateCcw, SlidersHorizontal, Trash2, Upload } from 'lucide-react';
import { API_BASE } from '../api/client';
import { cropBounds, cropChampionshipImage, prepareChampionshipImage } from '../lib/championshipImage';

export function ChampionshipImagePicker({ kind, value, disabled, onChange, onBusy }: {
  kind: 'profile' | 'cover'; value?: string | null; disabled: boolean;
  onChange: (data: string) => void; onBusy: (busy: boolean) => void;
}) {
  const profile = kind === 'profile';
  const title = profile ? 'Foto de perfil' : 'Capa do campeonato';
  const aspect = profile ? 1 : 4;
  const input = useRef<HTMLInputElement>(null);
  const sequence = useRef(0);
  const originalUrls = useRef(new Set<string>());
  const [draft, setDraft] = useState<string | null>(null);
  const [original, setOriginal] = useState<string | null>(null);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState('');
  const [dragOver, setDragOver] = useState(false);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const [position, setPosition] = useState({ zoom: 1, x: 50, y: 50 });
  const appliedPosition = useRef({ zoom: 1, x: 50, y: 50 });
  const drag = useRef<{ id: number; x: number; y: number; startX: number; startY: number } | null>(null);
  const src = value?.startsWith('/api/public/championship-images/') ? `${API_BASE}${value}`
    : value?.startsWith('https://') || /^data:image\/(jpeg|png);base64,/.test(value ?? '') ? value : null;
  useEffect(() => { onBusy(working || draft !== null); }, [working, draft, onBusy]);
  useEffect(() => {
    for (const url of originalUrls.current) {
      if (url !== draft && url !== original) { URL.revokeObjectURL(url); originalUrls.current.delete(url); }
    }
  }, [draft, original]);
  useEffect(() => () => {
    sequence.current++;
    for (const url of originalUrls.current) URL.revokeObjectURL(url);
    originalUrls.current.clear();
  }, []);
  function begin(source: string) {
    setSize({ width: 0, height: 0 }); setPosition({ zoom: 1, x: 50, y: 50 });
    setDraft(source); setError('');
  }
  async function select(file?: File) {
    if (!file || disabled || working) return;
    const ticket = ++sequence.current;
    setWorking(true); setError('');
    try {
      const prepared = await prepareChampionshipImage(file);
      if (ticket === sequence.current) { originalUrls.current.add(prepared); begin(prepared); }
      else URL.revokeObjectURL(prepared);
    } catch { if (ticket === sequence.current) setError('Não foi possível abrir a imagem. Use JPG, PNG ou WebP de até 6 MB e 40 megapixels.'); }
    finally { if (ticket === sequence.current) setWorking(false); }
  }
  async function apply() {
    if (!draft) return;
    const ticket = ++sequence.current;
    setWorking(true); setError('');
    try {
      const cropped = await cropChampionshipImage(draft, aspect, position.zoom, position.x, position.y);
      if (ticket === sequence.current) {
        appliedPosition.current = position; setOriginal(draft); onChange(cropped); setDraft(null);
      }
    } catch (error) {
      if (ticket === sequence.current) setError(error instanceof Error && error.message.startsWith('O recorte excede')
        ? error.message : 'Não foi possível ajustar esta imagem. Envie o arquivo novamente.');
    }
    finally { if (ticket === sequence.current) setWorking(false); }
  }
  const crop = size.width ? cropBounds(size.width, size.height, aspect, position.zoom, position.x, position.y) : null;
  function move(event: PointerEvent<HTMLDivElement>) {
    if (!drag.current || !crop) return;
    const frame = event.currentTarget.getBoundingClientRect();
    const scale = frame.width / crop.width;
    const dx = size.width - crop.width; const dy = size.height - crop.height;
    setPosition(p => ({ ...p,
      x: dx > 0 ? Math.max(0, Math.min(100, drag.current!.startX - (event.clientX - drag.current!.x) / scale / dx * 100)) : 50,
      y: dy > 0 ? Math.max(0, Math.min(100, drag.current!.startY - (event.clientY - drag.current!.y) / scale / dy * 100)) : 50 }));
  }
  return <section className={`image-picker image-picker--${kind}`} aria-labelledby={`image-${kind}-title`} aria-busy={working}>
    <header className="image-picker__heading"><div><h3 id={`image-${kind}-title`}>{title}</h3>
      <p>{profile ? 'O símbolo do seu evento, ao lado do nome.' : 'Uma imagem panorâmica para dar destaque ao campeonato.'}</p></div>
      <span className="image-picker__ratio">{profile ? '1:1' : '4:1'}</span></header>
    <input ref={input} id={`image-${kind}-file`} className="sr-only" type="file" tabIndex={-1}
      aria-label={`Selecionar ${title.toLowerCase()}`} accept="image/jpeg,image/png,image/webp" disabled={disabled || working}
      onChange={e => { void select(e.target.files?.[0]); e.target.value = ''; }} />
    {draft ? <div className="image-crop">
      <div className="image-crop__frame" style={{ aspectRatio: aspect }}
        onPointerDown={e => { if (working) return; e.currentTarget.setPointerCapture(e.pointerId);
          drag.current = { id: e.pointerId, x: e.clientX, y: e.clientY, startX: position.x, startY: position.y }; }}
        onPointerMove={e => { if (drag.current?.id === e.pointerId) move(e); }}
        onPointerUp={() => { drag.current = null; }} onPointerCancel={() => { drag.current = null; }}>
        <img src={draft} crossOrigin="anonymous" alt={`Enquadramento da ${title.toLowerCase()}`} draggable={false}
          onLoad={e => setSize({ width: e.currentTarget.naturalWidth, height: e.currentTarget.naturalHeight })}
          onError={() => setError('Não foi possível abrir esta imagem. Envie o arquivo novamente.')}
          style={crop ? { width: `${size.width / crop.width * 100}%`, height: `${size.height / crop.height * 100}%`,
            left: `${-crop.x / crop.width * 100}%`, top: `${-crop.y / crop.height * 100}%` } : { opacity: 0 }} />
        <span className="image-crop__grid" aria-hidden />
      </div>
      <p className="image-picker__hint"><Move size={14} aria-hidden /> Arraste a imagem ou use os controles para posicionar.</p>
      <div className="image-crop__controls">
        <label htmlFor={`crop-${kind}-zoom`}>Zoom <output>{position.zoom.toFixed(1)}×</output></label>
        <input id={`crop-${kind}-zoom`} type="range" min="1" max="3" step="0.01" value={position.zoom} disabled={working}
          onChange={e => setPosition(p => ({ ...p, zoom: Number(e.target.value) }))} />
        <label htmlFor={`crop-${kind}-x`}>Posição horizontal</label>
        <input id={`crop-${kind}-x`} type="range" min="0" max="100" value={position.x} disabled={working}
          onChange={e => setPosition(p => ({ ...p, x: Number(e.target.value) }))} />
        <label htmlFor={`crop-${kind}-y`}>Posição vertical</label>
        <input id={`crop-${kind}-y`} type="range" min="0" max="100" value={position.y} disabled={working}
          onChange={e => setPosition(p => ({ ...p, y: Number(e.target.value) }))} />
      </div>
      <div className="image-picker__actions">
        <button type="button" className="btn btn--ghost" disabled={working} onClick={() => setPosition({ zoom: 1, x: 50, y: 50 })}><RotateCcw size={15} aria-hidden /> Centralizar</button>
        <button type="button" className="btn btn--ghost" disabled={working} onClick={() => { setDraft(null); setError(''); }}>Cancelar</button>
        <button type="button" className="btn btn--primary" disabled={working || !size.width} onClick={() => void apply()}>{working ? 'Preparando…' : 'Aplicar recorte'}</button>
      </div>
    </div> : <>
      <button type="button" className={`image-picker__drop${dragOver ? ' is-dragging' : ''}${src ? ' has-image' : ''}`}
        disabled={disabled || working} onClick={() => input.current?.click()}
        onDragOver={e => { e.preventDefault(); if (!disabled && !working) setDragOver(true); }}
        onDragLeave={() => setDragOver(false)} onDrop={e => { e.preventDefault(); setDragOver(false); void select(e.dataTransfer.files[0]); }}>
        {src ? <img src={src} alt={`Prévia da ${title.toLowerCase()}`} /> : <><ImagePlus size={28} aria-hidden /><strong>{working ? 'Preparando imagem…' : 'Clique ou arraste uma imagem'}</strong><span>JPG, PNG ou WebP · até 6 MB</span></>}
        {src && <span className="image-picker__replace"><Upload size={16} aria-hidden /> Trocar imagem</span>}
      </button>
      <p className="image-picker__hint">{profile ? 'Recomendado: 1024 × 1024 px. Use o logo ou a arte original do evento.' : 'Recomendado: 2048 × 512 px. Prefira a arte original em formato horizontal.'}</p>
      {src && <div className="image-picker__actions">
        <button type="button" className="btn btn--ghost" disabled={disabled || working} onClick={() => {
          begin(original || src); if (original) setPosition(appliedPosition.current);
        }}><SlidersHorizontal size={15} aria-hidden /> Ajustar enquadramento</button>
        <button type="button" className="btn btn--ghost" disabled={disabled || working} onClick={() => { setOriginal(null); setError(''); onChange(''); }}><Trash2 size={15} aria-hidden /> Remover</button>
      </div>}
    </>}
    {error && <p className="form-error" role="alert">{error}</p>}
  </section>;
}
