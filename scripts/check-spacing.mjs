#!/usr/bin/env node
// Verifica las reglas de espaciado de .claude/skills/bpm-spacing/SKILL.md sobre el código Compose.
// Uso: node scripts/check-spacing.mjs [--strict]   (con --strict las advertencias también fallan)
import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(fileURLToPath(new URL('.', import.meta.url)), '..');
const source = join(root, 'composeApp/src/commonMain/kotlin');
/** Escala permitida en dp para relleno y separación (96 reserva espacio al botón flotante). */
const scale = new Set([0, 1, 2, 3, 4, 6, 8, 10, 12, 14, 16, 18, 20, 24, 28, 32, 40, 48, 56, 64, 96]);
/** Excepciones documentadas: tokens y composición dividida del login (48/24 dp por panel). */
const exempt = /(designsystem[\\/]BpmTheme|identity[\\/]presentation[\\/]IdentityScreen)\.kt$/;
const strict = process.argv.includes('--strict');
const updateBaseline = process.argv.includes('--update-baseline');
const baselinePath = join(root, 'scripts/spacing-baseline.json');

const walk = (dir) => readdirSync(dir).flatMap((name) => {
  const path = join(dir, name);
  return statSync(path).isDirectory() ? walk(path) : path.endsWith('.kt') ? [path] : [];
});

const errors = [];
const warnings = [];
for (const file of walk(source)) {
  const rel = relative(root, file).replaceAll('\\', '/');
  const text = readFileSync(file, 'utf8');
  const lineOf = (offset) => text.slice(0, offset).split('\n').length;
  // R5 (multilínea): `forEach { x ->` seguido, aunque sea en otra línea, de una tarjeta a ancho completo.
  for (const match of text.matchAll(/forEach\s*\{[^}\n]*->\s*\n?\s*BpmCard\(Modifier\.fillMaxWidth\(\)/g)) {
    const first = lineOf(match.index);
    const marked = text.split('\n').slice(first - 1, first + 1).some((l) => l.includes('lista-apilada'));
    if (!marked) warnings.push(`${rel}:${first} R5 lista de tarjetas apiladas; usar BpmCardGrid o marcar // lista-apilada`);
  }
  // R6: tres o más chips en una Row rígida se cortan a 360 dp; deben ir en FlowRow.
  for (const match of text.matchAll(/\bRow\([^\n]*\{\s*\n((?:[^\n]*\n){1,6})/g)) {
    if ((match[1].match(/FilterChip\(/g) ?? []).length >= 3)
      warnings.push(`${rel}:${lineOf(match.index)} R6 tres o más chips en Row; usar FlowRow`);
  }
  // R11: una tarjeta a ancho completo con un solo Text es una franja o una fila, no una tarjeta.
  if (!exempt.test(file))
    for (const match of text.matchAll(/BpmCard\(Modifier\.fillMaxWidth\(\)\)\s*\{\s*\n\s*Text\([^\n]*\n\s*\}/g))
      warnings.push(`${rel}:${lineOf(match.index)} R11 tarjeta de ancho completo con un solo Text; usar BpmInlineNotice o una fila`);
  text.split('\n').forEach((line, index) => {
    const at = `${rel}:${index + 1}`;
    const code = line.replace(/\/\/.*$/, '');
    const isTheme = /designsystem[\\/](BpmTheme|BpmButtons|Components|BpmLayoutComponents|BpmFilterChip)\.kt$/.test(file);
    // R7: el botón principal solo mide a contenido (mínimo 160 dp) o a ancho completo.
    if (!isTheme && /RaisedButton\(/.test(code) && /\.(?:width|widthIn|sizeIn)\(/.test(code)
      && !/widthIn\(min\s*=\s*160\.dp\)/.test(code))
      warnings.push(`${at} R7 botón principal con ancho fuera de la escala (mínimo 160 dp o ancho completo)`);
    // R8: el peso de letra viene del estilo del tema, no de cada pantalla.
    if (!isTheme && /\bfontWeight\s*=/.test(code))
      warnings.push(`${at} R8 fontWeight escrito a mano; usar el estilo del tema`);
    // R9: filtros con BpmFilterChip y tarjetas con BpmCard, nunca los de Material directos.
    if (!isTheme && /(?<![A-Za-z])(?:FilterChip|Card|ElevatedCard|OutlinedCard)\(/.test(code))
      warnings.push(`${at} R9 componente de Material directo; usar BpmFilterChip o BpmCard`);
    // R10: separaciones solo de la escala 4 · 8 · 12 · 16 · 24 · 32.
    for (const match of code.matchAll(/spacedBy\(\s*(\d+(?:\.\d+)?)\.dp/g))
      if (![4, 8, 12, 16, 24, 32].includes(Number(match[1])))
        warnings.push(`${at} R10 spacedBy de ${match[1]} dp fuera de la escala 4·8·12·16·24·32`);
    const inDesignSystem = /core[\\/]designsystem[\\/]/.test(file);
    // R12: las pantallas no miden la ventana; el ancho viene de LocalBpmWindowClass (BpmAppShell lo calcula una vez).
    // Se exime con `// medida: motivo` cuando el componente mide su propio contenedor (ilustración, ventana modal) y no la ventana.
    if (!inDesignSystem && /\bBoxWithConstraints\(/.test(code) && !line.includes('medida:') && !/workspace[\\/]presentation[\\/]WorkspaceScreen\.kt$/.test(file))
      warnings.push(`${at} R12 BoxWithConstraints en pantalla; usar un patrón (BpmScreen) y LocalBpmWindowClass`);
    // R13: toda rejilla de pantalla usa un patrón (BpmGridLayout o BpmPanelLayout), no BpmCardGrid directo.
    if (!inDesignSystem && /\bBpmCardGrid\(/.test(code) && !line.includes('patrón:'))
      errors.push(`${at} R13 BpmCardGrid directo; usar BpmGridLayout/BpmPanelLayout o marcar // patrón: motivo`);
    // R1: el margen lateral de pantalla sale de BpmSpacing.gutter, no de literales por pantalla.
    if (!exempt.test(file) && /if\s*\(\s*wide\s*\)\s*\d+\.dp\s*else\s*\d+\.dp/.test(code))
      errors.push(`${at} R1 margen lateral con literales; usar BpmSpacing.gutter(wide)`);
    // R4: una pantalla alojada en el contenedor del workspace no repite el margen lateral.
    if (/Screen\.kt$/.test(file) && /verticalScroll\([^)]*\)\)?\.padding\((?:all\s*=\s*)?\d+\.dp\)/.test(code))
      errors.push(`${at} R4 relleno propio en pantalla alojada; el margen lo aporta el contenedor`);
    // R5 en una sola línea (la variante multilínea se evalúa arriba sobre el texto completo).
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

// Compuerta de CI: los avisos existentes se congelan en una línea base por archivo; ningún archivo puede sumar avisos nuevos.
const perFile = {};
for (const message of warnings) { const file = message.split(':')[0]; perFile[file] = (perFile[file] ?? 0) + 1; }
if (updateBaseline) {
  writeFileSync(baselinePath, JSON.stringify(Object.fromEntries(Object.entries(perFile).sort()), null, 2) + '\n');
  console.log(`Línea base actualizada: ${Object.keys(perFile).length} archivos.`);
} else if (existsSync(baselinePath)) {
  const baseline = JSON.parse(readFileSync(baselinePath, 'utf8'));
  for (const [file, count] of Object.entries(perFile))
    if (count > (baseline[file] ?? 0))
      errors.push(`${file} suma avisos nuevos (${count} frente a ${baseline[file] ?? 0} de la línea base); corrígelos o justifícalos con --update-baseline`);
}

for (const message of errors) console.error(`ERROR  ${message}`);
for (const message of warnings) console.warn(`AVISO  ${message}`);
console.log(`Espaciado: ${errors.length} errores, ${warnings.length} avisos.`);
process.exit(errors.length || (strict && warnings.length) ? 1 : 0);
