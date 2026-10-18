// This target runs in a browser. Ktor's Wasm logger probes a bare `process`
// identifier; browsers do not define Node's process global. Resolve that probe
// at build time, without injecting Node polyfills or exposing build-machine env.
config.plugins.push(new (require('webpack').DefinePlugin)({
    process: 'undefined',
}));
