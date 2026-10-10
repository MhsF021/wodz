import { Navigate, useSearchParams } from 'react-router';
import { payoutReturnDestination } from '../lib/payoutReturn';

/** O callback remove código e state; Minha Conta confirma o estado pela API. */
export default function MercadoPagoReturnPage() {
  const [params] = useSearchParams();
  return <Navigate to={payoutReturnDestination(params)} replace />;
}
