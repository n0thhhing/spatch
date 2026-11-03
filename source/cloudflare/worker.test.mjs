// node --test source/cloudflare/  (GitHub is faked; nothing leaves this machine)
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import worker, { allowed } from "./worker.js";
import index from "./index.json" with { type: "json" };

const OBJECT = "/" + index.sources[0].urls[0];
const env = {
  GITHUB_TOKEN: "test-token",
  REPO: "owner/private",
  TAG: "apkm-1",
  ASSET: "spotify.apkm",
  CODE_alex: createHash("sha256").update("k7m2p9").digest("hex"),
};
const login = (name, code) => "Basic " + Buffer.from(`${name}:${code}`).toString("base64");
const get = (path, auth) => worker.fetch(new Request("https://source.example" + path, { headers: auth ? { Authorization: auth } : {} }), env);

let calls;
function github({ size = index.sources[0].size, status = 302, location = "https://objects.example/signed?x=1" } = {}) {
  calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ url: String(url), init });
    if (String(url).includes("/releases/tags/")) return Response.json({ assets: [{ name: "spotify.apkm", size, url: "https://api.github.com/repos/owner/private/releases/assets/9" }] });
    return new Response(null, { status, headers: location ? { Location: location } : {} });
  };
}

test("logins", async () => {
  assert.equal(await allowed(login("alex", "k7m2p9"), env), true);
  assert.equal(await allowed(login("alex", "wrong"), env), false);
  assert.equal(await allowed(login("sam", "k7m2p9"), env), false, "a name without a CODE_ secret");
  assert.equal(await allowed(login("GITHUB_TOKEN", "test-token"), env), false, "names can't reach other secrets");
  assert.equal(await allowed("Basic !!!", env), false);
  assert.equal(await allowed("Bearer k7m2p9", env), false);
  assert.equal(await allowed(null, env), false);
});

test("nothing is served without a login", async () => {
  github();
  for (const path of ["/index.json", OBJECT, "/"]) assert.equal((await get(path)).status, 401, path);
  assert.equal(calls.length, 0, "GitHub isn't asked");
});

test("the index", async () => {
  const r = await get("/index.json", login("alex", "k7m2p9"));
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), index);
});

test("the APKM redirects to GitHub's own short-lived link", async () => {
  github();
  const r = await get(OBJECT, login("alex", "k7m2p9"));
  assert.equal(r.status, 302);
  assert.equal(r.headers.get("Location"), "https://objects.example/signed?x=1");
  assert.equal(calls[1].init.headers.Authorization, "Bearer test-token");
  assert.equal(calls[1].init.redirect, "manual");
  assert.equal(calls[1].init.headers.Accept, "application/octet-stream");
});

test("a release that doesn't match the index is refused", async () => {
  github({ size: 1 });
  assert.equal((await get(OBJECT, login("alex", "k7m2p9"))).status, 502);
  github({ location: "http://insecure.example/file" });
  assert.equal((await get(OBJECT, login("alex", "k7m2p9"))).status, 502);
  github({ status: 200, location: null });
  assert.equal((await get(OBJECT, login("alex", "k7m2p9"))).status, 502);
});

test("other paths and methods", async () => {
  assert.equal((await get("/spotify-other.apkm", login("alex", "k7m2p9"))).status, 404);
  const post = await worker.fetch(new Request("https://source.example/index.json", { method: "POST", headers: { Authorization: login("alex", "k7m2p9") } }), env);
  assert.equal(post.status, 405);
});
