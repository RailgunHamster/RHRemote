// Exercise the actual browser script with a deterministic clock and minimal DOM.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync(require('node:path').join(__dirname, '../workload/pattern.html'), 'utf8');
const code = html.match(/<script>([\s\S]*?)<\/script>/)[1];
let now = 0, nextFrame, exported;
const context2d = new Proxy({}, { get: () => () => {} });
const canvas = { getContext: () => context2d };
const sandbox = {
  URLSearchParams, location: { search: '?warmup=1&measure=3&fps=60&auto=1' },
  performance: { now: () => now }, navigator: { userAgent: 'test' },
  screen: { width: 1920, height: 1080 },
  window: { innerWidth: 1920, innerHeight: 1080, devicePixelRatio: 1, addEventListener() {} },
  document: { getElementById: () => canvas, addEventListener() {}, createElement: kind => kind === 'canvas' ? canvas : { click() {} } },
  Blob: class { constructor(parts) { exported = JSON.parse(parts[0]); } },
  URL: { createObjectURL: () => 'blob:test', revokeObjectURL() {} }, setTimeout() {},
  requestAnimationFrame: cb => { nextFrame = cb; },
};
vm.createContext(sandbox);
vm.runInContext(code, sandbox);
vm.runInContext('let sortCalls = 0; const originalSort = Array.prototype.sort; Array.prototype.sort = function(...args) { sortCalls++; return originalSort.apply(this, args); };', sandbox);
for (let i = 0; i < 200; i++) { now = i * 1000 / 60; nextFrame(now); }
assert.equal(vm.runInContext('sortCalls', sandbox), 0, 'render loop must never sort accumulated samples');
assert.equal(vm.runInContext('liveMeasure.count', sandbox), sandbox.window.__rhWorkload.intervals.measure.length);
now += 80; nextFrame(now);
assert.equal(vm.runInContext('liveMeasure.over_50ms', sandbox), 1, 'stalls remain in measurements');
for (let i = 0; i < 60; i++) { now += 1000 / 60; nextFrame(now); }
assert.equal(exported.phase_at_export, 'done');
assert.equal(exported.measure.stats.over_50ms, 1);
assert.equal(exported.measure.stats.count, exported.measure.intervals_ms.length);
assert.ok(exported.measure.stats.p99_ms > 0);
sandbox.window.__rhWorkload.startRun();
assert.equal(vm.runInContext('liveMeasure.count', sandbox), 0);
assert.equal(sandbox.window.__rhWorkload.intervals.measure.length, 0);
console.log('PASS: constant-time render statistics, complete export, stall retention, restart reset.');
