// Run the owned page's actual script against controlled media events, without a browser.
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const path = require("node:path");
const source = fs.readFileSync(path.join(__dirname, "browser-movie-source.html"), "utf8")
  .match(/<script[^>]*>([\s\S]*?)<\/script>/)[1]
  .replace("__RH_MOVIE_CONFIG__", JSON.stringify({width: 1920, height: 1080, seconds: 120, startSeconds: 30}));
function page({callbacks = true} = {}) {
  const handlers = new Map(), packets = [];
  let nextFrame = null, stop = false, now = 0;
  const video = {videoWidth: 1920, videoHeight: 1080, currentTime: 0, playbackRate: 1,
    paused: true, seeking: false, readyState: 4, muted: false, volume: 1,
    addEventListener(name, fn) { handlers.set(name, fn); },
    async play() { this.paused = false; }, pause() { this.paused = true; },
    getVideoPlaybackQuality() { return {totalVideoFrames: 1, droppedVideoFrames: 0}; }};
  if (callbacks) video.requestVideoFrameCallback = fn => { nextFrame = fn; };
  Object.defineProperty(video, "src", {set(value) {
    assert.equal(this.muted, true, "Media URL was attached before mute");
    assert.equal(this.defaultMuted, true); assert.equal(this.volume, 0); assert.equal(value, "asset.mp4");
  }});
  const context = vm.createContext({document: {getElementById: () => video, visibilityState: "visible",
      hasFocus: () => true, addEventListener() {}},
    performance: {now: () => now, timeOrigin: 1000}, innerWidth: 1920, innerHeight: 1080, devicePixelRatio: 1,
    setInterval() {}, async fetch(url, request) {
      assert.equal(url, "telemetry"); packets.push(JSON.parse(request.body));
      return {ok: true, async json() { return {stop}; }};
    }});
  vm.runInContext(source, context);
  return {video, handlers, packets, get: expression => vm.runInContext(expression, context),
    frame(meta) { now += 16.667; nextFrame(now, meta); }, requestStop() { stop = true; }};
}
async function main() {
  const p = page();
  await new Promise(resolve => setImmediate(resolve));
  p.handlers.get("loadedmetadata")();
  await p.handlers.get("seeked")();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(p.get("phase"), "waiting_for_frame", "play() completion was confused with a first composed frame");
  assert.equal(p.get("lastFrame"), null);
  p.frame({mediaTime: 30, presentedFrames: 1, presentationTime: 16, expectedDisplayTime: 33,
    width: 1920, height: 1080});
  assert.equal(p.get("phase"), "playing");
  await p.get("send()");
  assert.equal(p.packets.at(-1).frames[0].processingDuration, null, "Unknown decode duration became zero");
  assert.equal(p.packets.at(-1).callbackCount, 1);
  p.requestStop(); await p.get("send()");
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(p.get("ended"), true); assert.equal(p.video.paused, true);
  assert.equal(p.packets.at(-1).phase, "ended");
  for (const packet of p.packets) assert.equal(packet.muted, true);
  const missing = page({callbacks: false});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(missing.get("phase"), "error");
  assert.match(missing.get("failure"), /unavailable/);
  assert.equal(missing.packets.at(-1).frames.length, 0);
  process.stdout.write("Browser movie page: mute-before-load, first-composition readiness, unknown duration, controlled stop and missing API checks passed\n");
}
main().catch(error => { console.error(error); process.exitCode = 1; });
