// SDK oficial fijado. No cargar Analytics ni pedir acceso a Drive/Gmail.
import { initializeApp } from 'https://www.gstatic.com/firebasejs/13.0.0/firebase-app.js';
import { initializeAuth, inMemoryPersistence, browserPopupRedirectResolver,
  GoogleAuthProvider, signInWithPopup, signOut } from 'https://www.gstatic.com/firebasejs/13.0.0/firebase-auth.js';
import { createGoogleAuthController } from './google-controller.mjs';

window.bpmGoogleAuth = createGoogleAuthController({
  sdk: { initializeApp, initializeAuth, inMemoryPersistence,
    browserPopupRedirectResolver, GoogleAuthProvider, signInWithPopup, signOut },
  loadConfig: async () => {
    const response = await fetch('/config.json', { cache: 'no-store', credentials: 'omit' });
    if (!response.ok) throw new Error('Public configuration unavailable');
    return response.json();
  },
});
