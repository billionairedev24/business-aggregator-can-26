import { Redirect } from 'expo-router';

import { useAuth } from '../src/auth/AuthProvider';

/** Where the app opens: Welcome the first time (no sign-in yet), Home afterwards — guests may browse (design 01 A1). */
export default function Entry() {
  const { status, welcomed } = useAuth();
  return <Redirect href={status === 'signedIn' || welcomed ? '/home' : '/welcome'} />;
}
