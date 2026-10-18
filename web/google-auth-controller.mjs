// Puente independiente del navegador y del SDK concreto, probado con dobles.
// Nunca guardar credenciales en localStorage, cookies ni logs.
export function createGoogleAuthController({ sdk, loadConfig }) {
  let auth;
  let ready = false;
  let initializationCode = 'auth/not-ready';
  let generation = 0;
  let running = false;
  const error = code => Object.assign(new Error('Google sign-in failed'), { code });
  const initialization = (async () => {
    try {
      const config = await loadConfig();
      if (!config.firebaseApiKey || !/^[a-z][a-z0-9-]{4,62}$/.test(config.firebaseProjectId) ||
          config.firebaseAuthDomain !== config.firebaseProjectId + '.firebaseapp.com') {
        initializationCode = 'auth/not-configured';
        return;
      }
      const app = sdk.initializeApp({ apiKey: config.firebaseApiKey,
        projectId: config.firebaseProjectId, authDomain: config.firebaseAuthDomain }, 'bpm-google');
      auth = sdk.initializeAuth(app, {
        persistence: sdk.inMemoryPersistence,
        popupRedirectResolver: sdk.browserPopupRedirectResolver,
      });
      ready = true;
    } catch {
      initializationCode = 'auth/unavailable';
    }
  })();
  return {
    initialization,
    async signIn() {
      // No await previo al popup: debe abrirse dentro del gesto de click.
      if (!ready) throw error(initializationCode);
      if (running) throw error('auth/in-progress');
      running = true;
      const attempt = ++generation;
      try {
        const provider = new sdk.GoogleAuthProvider();
        provider.setCustomParameters({ prompt: 'select_account' });
        const result = await sdk.signInWithPopup(auth, provider);
        const token = await result.user.getIdTokenResult();
        const expiresIn = String(Math.max(0, Math.floor((Date.parse(token.expirationTime) - Date.now()) / 1000)));
        if (attempt !== generation) throw error('auth/cancelled');
        // Se entrega el ID token Firebase, no el access token ni ID token crudo de Google.
        return JSON.stringify({ idToken: token.token, uid: result.user.uid, displayName: result.user.displayName || 'Usuario BPM',
          refreshToken: result.user.refreshToken, expiresIn });
      } catch (cause) {
        // Solo el código, nunca el mensaje, email ni credencial del proveedor.
        throw error(typeof cause?.code === 'string' ? cause.code : 'auth/unavailable');
      } finally {
        await sdk.signOut(auth).catch(() => {});
        running = false;
      }
    },
    clear() {
      generation++;
      // La operación activa limpia el SDK en finally. No lanzar un signOut
      // concurrente con el intercambio OAuth ni con el siguiente click.
      // Fuera de una operación el SDK ya está vacío (persistencia en memoria).
    },
  };
}
