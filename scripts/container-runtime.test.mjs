import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, mkdir, copyFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execute = promisify(execFile);
const repository = fileURLToPath(new URL('../', import.meta.url));

// Comprueba las copias reales de la etapa final sin depender de un daemon Docker.
test('el servidor arranca con los archivos declarados para el contenedor de producción', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'bpm-runtime-'));
  try {
    const dockerfile = await readFile(join(repository, 'Dockerfile'), 'utf8');
    const runtime = dockerfile.split(/^FROM /m).at(-1);
    for (const line of runtime.split('\n').filter(value => value.startsWith('COPY '))) {
      if (line.includes('--from=')) continue;
      const paths = line.split(/\s+/).slice(1).filter(value => !value.startsWith('--'));
      const target = paths.pop();
      assert.ok(target?.startsWith('./'), 'La copia debe estar bajo WORKDIR /app');
      for (const source of paths) {
        const destination = resolve(directory, target, ...(target.endsWith('/') ? [basename(source)] : []));
        assert.ok(!relative(directory, destination).startsWith('..'));
        await mkdir(dirname(destination), { recursive: true });
        await copyFile(join(repository, source), destination);
      }
    }
    await mkdir(join(directory, 'public'));
    await writeFile(join(directory, 'public/index.html'), '<!doctype html><title>Prueba aislada</title>');
    const { stdout } = await execute(process.execPath, ['--input-type=module', '--eval', `
      import assert from 'node:assert/strict';
      import { once } from 'node:events';
      import { createAppServer } from './web/server.mjs';
      const server = createAppServer({distDir:'./public',apiBaseUrl:'https://api.example.invalid',firebaseApiKey:''});
      try {
        server.listen(0,'127.0.0.1'); await once(server,'listening');
        const origin = 'http://127.0.0.1:' + server.address().port;
        const response = await fetch(origin+'/healthz');
        assert.equal(response.status,200); assert.deepEqual(await response.json(),{status:'ok'});
        for (const path of ['/auth/google.mjs','/auth/google-controller.mjs']) {
          const script = await fetch(origin+path); assert.equal(script.status,200); await script.text();
        }
        console.log('runtime correcto');
      } finally { server.closeAllConnections(); await new Promise(resolve=>server.close(resolve)); }
    `], { cwd: directory, timeout: 10000, maxBuffer: 8192,
      env: { NODE_ENV: 'production', API_BASE_URL: 'https://api.example.invalid', FIREBASE_API_KEY: '' } });
    assert.match(stdout, /runtime correcto/);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
