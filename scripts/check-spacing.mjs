#!/usr/bin/env node
// Verifica las reglas de espaciado de .claude/skills/bpm-spacing/SKILL.md sobre el código Compose.
// Uso: node scripts/check-spacing.mjs [--strict]   (con --strict las advertencias también fallan)
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const source = join(root, 'composeApp/src/commonMain/kotlin');
/** Escala permitida en dp para relleno y separación (96 reserva espacio al botón flotante). */
const scale = new Set([0, 1, 2, 3, 4, 6, 8, 10, 12, 14, 16, 18, 20, 24, 28, 32, 40, 48, 56, 64, 96]);
/** Excepciones documentadas: tokens y composición dividida del login (48/24 dp por panel). */
const exempt = /(designsystem[\\/]BpmTheme|identity[\\/]presentation[\\/]IdentityScreen)\.kt$/;
const strict = process.argv.includes('--strict');

const walk = (dir) => readdirSync(dir).flatMap((name) => {
  const path = join(dir, name);
  return statSync(path).isDirectory() ? walk(path) : path.endsWith('.kt') ? [path] : [];
});

const errors = [];
const warnings = [];
for (const file of walk(source)) {
  const rel = relative(root, file).replaceAll('\\', '/');
  readFileSync(file, 'utf8').split('\n').forEach((line, index) => {
    const at = `${rel}:${index + 1}`;
    const code = line.replace(/\/\/.*$/, '');
    // R1: el margen lateral de pantalla sale de BpmSpacing.gutter, no de literales por pantalla.
    if (!exempt.test(file) && /if\s*\(\s*wide\s*\)\s*\d+\.dp\s*else\s*\d+\.dp/.test(code))
      errors.push(`${at} R1 margen lateral con literales; usar BpmSpacing.gutter(wide)`);
    // R4: una pantalla alojada en el contenedor del workspace no repite el margen lateral.
    if (/Screen\.kt$/.test(file) && /verticalScroll\([^)]*\)\)?\.padding\((?:all\s*=\s*)?\d+\.dp\)/.test(code))
      errors.push(`${at} R4 relleno propio en pantalla alojada; el margen lo aporta el contenedor`);
    // R5: listas de registros en tarjetas van en BpmCardGrid; las secuenciales se marcan con // lista-apilada.
    if (/forEach\s*\{[^}]*->\s*BpmCard\(Modifier\.fillMaxWidth\(\)\)/.test(code) && !line.includes('lista-apilada'))
      warnings.push(`${at} R5 lista de tarjetas apiladas; usar BpmCardGrid o marcar // lista-apilada`);
    // R2: relleno y separación dentro de la escala.
    for (const match of code.matchAll(/(?:padding|spacedBy|offset)\s*\([^)]*?\)/g))
      for (const value of match[0].matchAll(/(\d+(?:\.\d+)?)\.dp/g))
        if (!scale.has(Number(value[1])))
          warnings.push(`${at} R2 ${value[1]} dp fuera de la escala de espaciado`);
    // R3: ningún control interactivo por debajo de 48 dp de alto.
    for (const match of code.matchAll(/(?:heightIn\(min\s*=|minimumInteractiveComponentSize|defaultMinSize\(minHeight\s*=)\s*(\d+)\.dp/g))
      if (Number(match[1]) < 48 && /clickable|Button|Row|Switch|Checkbox|Chip/.test(code))
        warnings.push(`${at} R3 control con alto mínimo de ${match[1]} dp; el mínimo es 48 dp`);
  });
}

for (const message of errors) console.error(`ERROR  ${message}`);
for (const message of warnings) console.warn(`AVISO  ${message}`);
console.log(`Espaciado: ${errors.length} errores, ${warnings.length} avisos.`);
process.exit(errors.length || (strict && warnings.length) ? 1 : 0);
