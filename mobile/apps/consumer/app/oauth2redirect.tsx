import { Redirect } from 'expo-router';

/**
 * `ca.northline.app:/oauth2redirect?code=…` (and the claimed `https://<site>/app/oauth2redirect`) also reach the router:
 * Android hands a Custom Tab's redirect to the app as a link. The sign-in itself completes in the auth session's
 * promise (src/auth/AuthProvider.tsx); this route only moves on.
 */
export default function OAuthRedirect() {
  return <Redirect href="/home" />;
}
