import test from 'node:test';
import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';

const sourceRoot = new URL('../composeApp/src/', import.meta.url);
async function kotlinFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = await Promise.all(entries.map(entry => {
    const path = new URL(entry.name + (entry.isDirectory() ? '/' : ''), directory);
    return entry.isDirectory() ? kotlinFiles(path) : entry.name.endsWith('.kt') ? [path] : [];
  }));
  return files.flat();
}

// Scan production sources only; tests may intentionally wire every layer.
test('production dependencies preserve feature and layer boundaries on every platform', async () => {
  const violations = [];
  const roots = (await readdir(sourceRoot, { withFileTypes: true }))
    .filter(entry => entry.isDirectory() && entry.name.endsWith('Main'));
  for (const root of roots) {
    for (const file of await kotlinFiles(new URL(`${root.name}/`, sourceRoot))) {
      const source = await readFile(file, 'utf8');
      const pkg = source.match(/^package\s+([\w.]+)/m)?.[1];
      if (!pkg) continue;
      const imports = [...source.matchAll(/^import\s+([\w.*]+)/gm)].map(match => match[1]);
      const feature = pkg.match(/^pe\.bpm\.features\.([^.]+)\.(domain|data|presentation)(?:\.|$)/);
      const report = rule => violations.push(`${file.pathname}: ${rule}`);
      if (feature) {
        const [, name, layer] = feature;
        for (const dependency of imports) {
          const other = dependency.match(/^pe\.bpm\.features\.([^.]+)\.([^.]+)/);
          if (other && other[1] !== name) report(`feature ${name} depends on ${other[1]}; compose in app or DI`);
          if (dependency.startsWith('pe.bpm.app.') || dependency.startsWith('pe.bpm.core.di.')) report('feature depends on composition root');
          if (layer === 'domain' && /^(android\.|androidx\.|platform\.|io\.ktor\.|org\.jetbrains\.compose\.|kotlinx\.serialization\.|java\.|javax\.)/.test(dependency)) report(`domain imports infrastructure: ${dependency}`);
          if (layer === 'domain' && /^pe\.bpm\./.test(dependency) && !dependency.startsWith(`pe.bpm.features.${name}.domain.`) && !dependency.startsWith('pe.bpm.core.domain.')) report(`domain imports outer layer: ${dependency}`);
          if (layer === 'presentation' && (/^(io\.ktor\.|kotlinx\.serialization\.)/.test(dependency) || dependency.startsWith(`pe.bpm.features.${name}.data.`))) report(`presentation bypasses domain: ${dependency}`);
          if (layer === 'data' && (/^androidx\.compose\./.test(dependency) || dependency.startsWith(`pe.bpm.features.${name}.presentation.`))) report(`data depends on UI: ${dependency}`);
        }
      }
      if (pkg.startsWith('pe.bpm.core.') && !pkg.startsWith('pe.bpm.core.di') && imports.some(value => value.startsWith('pe.bpm.features.'))) report('shared core depends on a feature');
    }
  }
  assert.deepEqual(violations, [], violations.join('\n'));
});

// Regresión «los datos no cargan al navegar» (2026-11-23): `operations` e `incidents` son los mismos ViewModels de Hoy, Tareas, Turno y
// los reportes. Una pantalla secundaria que los reiniciaba en `onDispose` los vaciaba DESPUÉS de que la pantalla de destino ya había
// cargado (la saliente se retira al terminar la transición), y la destino quedaba sin datos. El reinicio es solo del cierre de sesión.
test('screens do not reset shared tab ViewModels when they leave the composition', async () => {
  const shared = ['operations', 'shifts', 'stock', 'incidents', 'trainingLearner', 'dailyOperations'];
  const offenders = [];
  for (const file of await kotlinFiles(new URL('commonMain/kotlin/pe/bpm/app/', sourceRoot))) {
    const text = await readFile(file, 'utf8');
    for (const match of text.matchAll(/onDispose\s*\{([^}]*)\}/g))
      for (const name of shared)
        if (new RegExp(`\\b${name}\\??\\.reset\\(\\)`).test(match[1])) offenders.push(`${file.pathname.split('/').pop()}: onDispose { ${name}.reset() }`);
  }
  assert.deepEqual(offenders, []);
});

// Regresión «Editar sede cerraba la app» (2026-11-23): la pantalla de cuentas desplaza en vertical; alojada en el espacio de Equipo (que ya
// desplaza el shell) anidaba dos desplazamientos y Compose lanza «measured with an infinity maximum height». Ahí va con scrollable = false.
test('accounts screen hosted inside the Team slot does not nest a second vertical scroll', async () => {
  const text = await readFile(new URL('commonMain/kotlin/pe/bpm/app/App.kt', sourceRoot), 'utf8');
  const start = text.indexOf('destination == WorkspaceDestination.Team && adminPlay');
  const hosted = start < 0 ? null : [text.slice(start, text.indexOf('}) else null', start))];
  assert.ok(hosted, 'no se encontró AccountsManagementContent en la ranura de Equipo');
  assert.match(hosted[0], /scrollable = false/);
});
