import { Redirect } from 'expo-router';

/**
 * `ca.northline.courier:/oauth2redirect?code=…` also reaches the router (Android hands the Custom Tab's redirect to
 * the app as a deep link). The sign-in itself completes in the auth session's promise (src/auth.tsx); this route only
 * moves on.
 */
export default function OAuthRedirect() {
  return <Redirect href="/" />;
}
