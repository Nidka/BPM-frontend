---
name: bpm-motion
description: Animaciones y transiciones de la UI BPM (Compose Multiplatform, móvil y web) - qué API usar, duraciones y curvas, entrada escalonada, estados, rendimiento y reducción de movimiento. Úsala al añadir o cambiar cualquier animación, transición, aparición de contenido o retroalimentación de pulsación, y al revisar que la UI no se sienta estática ni recargada.
---

# Movimiento BPM

El movimiento comunica **qué cambió y de dónde viene**, da retroalimentación inmediata y hace
que la app se sienta viva. No decora: si no explica un cambio de estado, no se anima.
Los tokens viven en `BpmMotion`
([BpmMotion.kt](../../../composeApp/src/commonMain/kotlin/pe/bpm/core/designsystem/BpmMotion.kt)).
Complementa a [bpm-spacing](../bpm-spacing/SKILL.md) (layout) y respeta la dirección lúdica de
`bpm-design-system/direccion-ludica.md`: alentador y breve, sin castigos ni exageración.

## 1. Elegir la API más pequeña

Principio (guía `compose-animations` de Chris Banes y documentación oficial de Compose): la
API más sencilla que exprese el movimiento **y** su ciclo de vida.

| Necesidad | Usar |
|---|---|
| Mostrar/ocultar un subárbol (se retira de la composición al salir) | `AnimatedVisibility` |
| Un valor sigue al estado (color, tamaño, desplazamiento) | `animate*AsState` |
| Varios valores siguen un mismo estado | `updateTransition` / `rememberTransition` |
| Cambia el tamaño de un contenedor | `Modifier.animateContentSize()` |
| Cambia el contenido de una región | `AnimatedContent` (o `Crossfade` si es simple) |
| Gesto, arrastre, interrupción, control imperativo | `Animatable` |
| Aparición de listas/rejillas | `Modifier.bpmAppear(index)` |

Reglas de ciclo de vida e identidad:
- Un fundido con `alpha` mantiene el contenido compuesto; si debe dejar de existir (y de ser
  enfocable por teclado o lector de pantalla), usar `AnimatedVisibility`.
- En `AnimatedContent` renderizar con el valor que recibe la lambda, **no** con el estado externo,
  o el contenido saliente mostrará datos nuevos. Usar `contentKey`/ID estable para distinguir
  elementos distintos.
- Dar un `label` distinto a cada animación simultánea (herramientas de depuración).
- No animar a la vez layout pesado en pantallas con listas largas.

## 2. Duración y curvas (Material 3)

| Token | ms | Uso |
|---|---|---|
| `SHORT` | 150 | pulsación, selección, color, icono |
| `MEDIUM` | 300 | aparición de contenido, cambio de tamaño |
| `LONG` | 450 | cambio de pantalla o bloque grande |
| `STAGGER` | 45 | retraso entre elementos; máximo `STAGGER_MAX_ITEMS` (6) para que la cascada no se alargue |

- **Entrar** desacelera (`BpmMotion.enter`); **salir** acelera y es más corto (≈ 60 % de la entrada).
- Posición y tamaño: **resorte** amortiguado (`BpmMotion.spatial()`), interrumpible y natural.
  Opacidad y color: `tween`.
- Nada supera 500 ms ni bloquea la interacción; la persona nunca espera a que termine una animación.
- Desplazamientos pequeños (≈ 16 dp). Escalas sutiles (0,96–1,0). Sin rebotes exagerados.

## 3. Dónde animar (checklist de «se siente estático»)

1. **Entrada de listas y rejillas:** cascada con `bpmAppear` (ya incluida en `BpmCardGrid`).
2. **Pulsación:** botones se hunden 2 dp en 100 ms (ya en `BpmButtons`); mantener ese patrón.
3. **Cambios de estado:** selección, interruptores, chips y filas (`animateColorAsState`
   con `SHORT`), contadores y progreso (`animate*AsState`).
4. **Contenido que aparece o desaparece:** avisos, errores, confirmaciones, filtros,
   secciones plegables → `BpmReveal { }` (fundido + despliegue corto, ya usado en avisos y errores del workspace).
5. **Cambio de tamaño:** tarjetas que se expanden, texto que pasa a varias líneas →
   `animateContentSize(BpmMotion.spatial())`.
6. **Transición entre pantallas:** fundido + desplazamiento vertical corto (`LONG`); la
   navegación principal no desliza horizontalmente en web. Usar las transiciones de Navigation
   Compose si la pantalla es destino suyo; no animar el `NavHost` completo (decisión ya
   documentada en `specs/WORKSPACE.md`).
7. **Retroalimentación positiva:** al completar una acción, un gesto breve de confirmación
   (check, pulso de color); la mascota refuerza, sin bloquear ni castigar el error.
8. **Carga:** `LinearProgressIndicator` solo para solicitudes reales; para esperar contenido,
   esqueleto o aparición, no un indicador falso.

## 4. Rendimiento

- Leer valores animados **en la fase de layout o dibujo** (`graphicsLayer { }`, `drawBehind`,
  `offset { }`), no en composición, para no recomponer en cada cuadro.
- Animar `alpha`, `translationX/Y`, `scale` y `rotation` con `graphicsLayer`; evitar animar
  tamaño y padding en contenedores grandes.
- No iniciar animaciones infinitas en pantallas con datos; si hay (carga, mascota), deben
  poder pausarse.
- Probar en web (wasm) y en un móvil de gama baja: sin cuadros perdidos al desplazarse.

## 5. Reducción de movimiento (obligatorio)

`rememberReduceMotion()` lee la preferencia del sistema: `prefers-reduced-motion` en web,
«Quitar animaciones» en Android y «Reducir movimiento» en iOS. Toda animación decorativa o
de desplazamiento debe comprobarla:
- sin movimiento: el contenido aparece al instante, **pero un cambio de estado sigue siendo
  visible** (color, icono, texto); no dejar la UI sin señal de que algo cambió;
- se conservan los fundidos suaves muy cortos si ayudan a entender; se eliminan el
  desplazamiento, el escalado y la cascada;
- nada parpadea más de 3 veces por segundo (WCAG 2.3.1) y todo lo que se mueve o se repite
  más de 5 s tiene control de pausa (WCAG 2.2.2).

## 6. Verificación

```bash
./gradlew :composeApp:jvmTest :composeApp:wasmJsBrowserDistribution :composeApp:assembleDebug
node scripts/check-spacing.mjs --strict
```

Luego, en el navegador (http://localhost:3000) y en un móvil o emulador:
- las listas aparecen en cascada al entrar y al buscar/paginar, sin saltos de layout;
- activar «reducir movimiento» (Chrome: DevTools → Rendering → *Emulate prefers-reduced-motion*;
  Android: Opciones de desarrollador → escala de animación 0×) y comprobar que todo aparece al instante;
- ninguna animación bloquea un botón ni retrasa la tarea; el foco por teclado sigue visible;
- la duración percibida es corta y consistente entre pantallas.

## Fuentes

- Chris Banes, skill [compose-animations](https://github.com/chrisbanes/skills) (principio de la API más pequeña, identidad en `AnimatedContent`, lectura en fases de layout/dibujo).
- Android Developers, [Animations in Compose](https://developer.android.com/develop/ui/compose/animation/introduction) y [Quick guide to animations](https://developer.android.com/develop/ui/compose/animation/quick-guide).
- Material Design 3, tokens de movimiento (duraciones corta/media/larga y curvas *emphasized*); valores contrastados en guías de terceros, verificar en m3.material.io ante una auditoría.
- W3C WCAG 2.2: 2.3.1 Three Flashes, 2.2.2 Pause/Stop/Hide, 2.3.3 Animation from Interactions.
