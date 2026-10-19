// Minimal LOKI backend. Keeps the AI provider key on the server; the app only holds a revocable token.
// Run behind HTTPS (Caddy, nginx, Cloud Run, Fly.io...). Requires Node 18+.
//   ANTHROPIC_API_KEY=... LOKI_TOKENS=tokenA,tokenB node server.js
import http from "node:http";

const KEY = process.env.ANTHROPIC_API_KEY;
const MODEL = process.env.LOKI_MODEL || "claude-sonnet-5-5";
const TOKENS = new Set((process.env.LOKI_TOKENS || "").split(",").filter(Boolean));
const hits = new Map();

const limited = (t) => {
  const now = Date.now();
  const a = (hits.get(t) || []).filter((x) => now - x < 60_000);
  a.push(now); hits.set(t, a);
  return a.length > 20; // 20 requests / minute / token
};

http.createServer(async (req, res) => {
  const send = (c, o) => { res.writeHead(c, { "content-type": "application/json" }); res.end(JSON.stringify(o)); };
  if (req.method !== "POST" || req.url !== "/ask") return send(404, {});
  const tok = (req.headers.authorization || "").replace(/^Bearer /, "");
  if (!TOKENS.has(tok)) return send(401, { error: "unauthorized" });
  if (limited(tok)) return send(429, { error: "slow down" });

  let body = "";
  for await (const c of req) { body += c; if (body.length > 8000) return send(413, {}); }
  try {
    const { prompt } = JSON.parse(body);
    const r = await fetch("https://api.anthropic.com/v1/messages", {
      method: "POST",
      headers: { "x-api-key": KEY, "anthropic-version": "2023-06-01", "content-type": "application/json" },
      body: JSON.stringify({
        model: MODEL,
        max_tokens: 300,
        system: "You are LOKI, a voice assistant. Answer in 1-3 short spoken sentences, no markdown. Reply in the user's language (Hindi, Hinglish or English).",
        messages: [{ role: "user", content: String(prompt).slice(0, 2000) }],
      }),
    });
    const j = await r.json();
    send(200, { reply: (j.content || []).map((b) => b.text || "").join("").trim() });
  } catch {
    send(502, { error: "upstream" });
  }
}).listen(process.env.PORT || 8080);
