import { useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import { publicApi } from '../api/endpoints';
import type { Gender, Role } from '../api/types';
import type { GoogleButtonText } from '../lib/googleIdentity';
import { localReturnPath } from '../lib/navigation';
import { GoogleButton } from './GoogleButton';

export function GoogleAuth({ role, gender, text = 'signin_with' }: { role?: Role; gender?: Gender; text?: GoogleButtonText }) {
  const [params] = useSearchParams();
  const config = useQuery({ queryKey: ['public-config'], queryFn: publicApi.config, staleTime: Infinity });
  const clientId = config.data?.googleClientId;
  if (!clientId) return null;
  return <div className="google-auth">
    <div className="form-divider"><span>ou</span></div>
    <GoogleButton clientId={clientId} text={text} flow={{ kind: 'login', role, gender, next: localReturnPath(params.get('next')) }} />
  </div>;
}
