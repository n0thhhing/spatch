// A private Spatch source on Cloudflare Workers. It answers only people with a login you gave them:
// /index.json lists every Spotify version the source keeps, and each version's APKM path redirects to
// a short-lived GitHub download of it from a private release. Logins and the GitHub token are Worker
// secrets; nothing here unlocks it.
//
// Secrets: GITHUB_TOKEN (read-only Contents on the private repo), and one CODE_<name> per person
// holding the SHA-256 hex of their code. Var (wrangler.toml): REPO. Each version's APKM is release
// apkm-<versionName>, asset spotify-<versionName>.apkm, so adding a version needs no edit here.

import index from "./index.json" with { type: "json" };

/** The Worker for a given index; the deployed one uses index.json. */
export function serve(index) {
  return {
    async fetch(request, env) {
      const path = new URL(request.url).pathname;
      if (request.method !== "GET" && request.method !== "HEAD") return reply(405);
      if (!(await allowed(request.headers.get("Authorization"), env))) return reply(401);
      if (path === "/index.json") {
        return new Response(JSON.stringify(index), { headers: { "Content-Type": "application/json", "Cache-Control": "no-store" } });
      }
      const entry = index.sources.find((s) => "/" + s.urls[0] === path);
      if (entry) return download(entry, env);
      return reply(404);
    },
  };
}

export default serve(index);

/** Basic name:code, where CODE_<name> holds the code's SHA-256. */
export async function allowed(header, env) {
  if (!header || !header.startsWith("Basic ")) return false;
  let login;
  try {
    login = atob(header.slice(6));
  } catch {
    return false;
  }
  const colon = login.indexOf(":");
  const name = login.slice(0, colon), code = login.slice(colon + 1);
  if (colon <= 0 || !/^[a-z0-9_-]{1,32}$/.test(name) || !code) return false;
  const want = env["CODE_" + name];
  if (typeof want !== "string") return false;
  return same(await sha256(code), want.trim().toLowerCase());
}

/** The release asset's own download: GitHub answers the API with a redirect to a link that expires in minutes. */
async function download(entry, env) {
  const headers = { Authorization: "Bearer " + env.GITHUB_TOKEN, "User-Agent": "spatch-source", "X-GitHub-Api-Version": "2022-11-28" };
  const release = await fetch(`https://api.github.com/repos/${env.REPO}/releases/tags/apkm-${entry.versionName}`, { headers: { ...headers, Accept: "application/vnd.github+json" } });
  if (!release.ok) return reply(502);
  const asset = (await release.json()).assets?.find((a) => a.name === `spotify-${entry.versionName}.apkm`);
  if (!asset || asset.size !== entry.size) return reply(502);
  const file = await fetch(asset.url, { headers: { ...headers, Accept: "application/octet-stream" }, redirect: "manual" });
  const location = file.headers.get("Location");
  if (file.status < 300 || file.status >= 400 || !location?.startsWith("https://")) return reply(502);
  return new Response(null, { status: 302, headers: { Location: location, "Cache-Control": "no-store" } });
}

async function sha256(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Equal-length hex compared without stopping at the first difference. */
function same(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function reply(status) {
  return new Response(null, { status, headers: { "Cache-Control": "no-store" } });
}
