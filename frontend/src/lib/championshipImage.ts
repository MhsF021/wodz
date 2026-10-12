/** Valida o original sem reduzir ou recomprimir; o chamador libera a URL ao terminar. */
export async function prepareChampionshipImage(file: File): Promise<string> {
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type) || file.size > 6 * 1024 * 1024) {
    throw new Error('Escolha uma imagem JPG, PNG ou WebP de até 6 MB');
  }
  const url = URL.createObjectURL(file);
  try {
    const image = new Image();
    image.src = url;
    await image.decode();
    if (!image.naturalWidth || !image.naturalHeight || image.naturalWidth * image.naturalHeight > 40_000_000) {
      throw new Error('Escolha uma imagem com até 40 megapixels');
    }
    return url;
  } catch (error) { URL.revokeObjectURL(url); throw error; }
}

/** Coordenadas normalizadas mantêm o mesmo recorte na prévia e no arquivo salvo. */
export function cropBounds(width: number, height: number, aspect: number, zoom: number, x: number, y: number) {
  const cropWidth = Math.min(width, height * aspect) / zoom;
  const cropHeight = cropWidth / aspect;
  return { x: (width - cropWidth) * x / 100, y: (height - cropHeight) * y / 100,
    width: cropWidth, height: cropHeight };
}

export async function cropChampionshipImage(src: string, aspect: number, zoom: number, x: number, y: number) {
  const image = new Image();
  image.crossOrigin = 'anonymous';
  image.src = src;
  await image.decode();
  const crop = cropBounds(image.naturalWidth, image.naturalHeight, aspect, zoom, x, y);
  const canvas = document.createElement('canvas');
  // Recorta antes de reduzir e não amplia imagens com poucos pixels.
  const targetWidth = aspect === 1 ? 1024 : 2048;
  canvas.width = Math.max(aspect, Math.floor(Math.min(targetWidth, crop.width) / aspect) * aspect);
  canvas.height = Math.round(canvas.width / aspect);
  const context = canvas.getContext('2d');
  if (!context) throw new Error('Não foi possível preparar a imagem');
  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = 'high';
  context.drawImage(image, crop.x, crop.y, crop.width, crop.height, 0, 0, canvas.width, canvas.height);
  // Logos, textos e artes simples ficam sem perda e com a transparência preservada.
  const png = canvas.toDataURL('image/png');
  if (png.length <= 900_000) return png;
  context.globalCompositeOperation = 'destination-over';
  context.fillStyle = '#ffffff';
  context.fillRect(0, 0, canvas.width, canvas.height);
  for (const quality of [0.96, 0.94, 0.92, 0.9]) {
    const data = canvas.toDataURL('image/jpeg', quality);
    // As duas imagens e os demais campos cabem no limite de 2 MB da API.
    if (data.length <= 900_000) return data;
  }
  throw new Error('O recorte excede o limite para salvar com boa qualidade. Escolha uma imagem com menos detalhes');
}
