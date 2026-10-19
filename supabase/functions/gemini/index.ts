// Proxy between the app and the Gemini API. The API key lives only here (as a Supabase
// secret set by GitHub Actions), so it is never shipped inside the APK.
//
// The app sends a `generateContent` request body; this function checks the caller's
// Supabase session, applies a per-user daily limit and forwards the request to Gemini.

const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY") ?? "";
const GEMINI_MODEL = Deno.env.get("GEMINI_MODEL") || "gemini-3.6-flash";
const DAILY_LIMIT = Number(Deno.env.get("AI_DAILY_LIMIT") || "150");
const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const MAX_BODY_BYTES = 20 * 1024 * 1024;

function error(status: number, message: string): Response {
  return new Response(JSON.stringify({ error: { code: status, message } }), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return error(405, "Método no permitido");
  if (!GEMINI_API_KEY) return error(500, "Falta el secreto GEMINI_API_KEY en Supabase");

  const authorization = req.headers.get("Authorization") ?? "";
  const apikey = req.headers.get("apikey") ?? "";
  if (!authorization.startsWith("Bearer ") || !apikey) return error(401, "Sesión requerida");

  // Validates the JWT (PostgREST rejects invalid tokens) and counts today's usage.
  const usage = await fetch(`${SUPABASE_URL}/rest/v1/rpc/bump_ai_usage`, {
    method: "POST",
    headers: { apikey, Authorization: authorization, "Content-Type": "application/json" },
    body: "{}",
  });
  if (usage.status === 401 || usage.status === 403) return error(401, "Sesión inválida o expirada");
  if (!usage.ok) return error(502, `No se pudo verificar el uso de IA (${usage.status})`);
  const calls = Number(await usage.json());
  if (calls > DAILY_LIMIT) {
    return error(429, `Llegaste al límite diario de ${DAILY_LIMIT} consultas de IA`);
  }

  const body = await req.text();
  if (body.length > MAX_BODY_BYTES) return error(413, "La solicitud es demasiado grande");

  const gemini = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json", "x-goog-api-key": GEMINI_API_KEY },
      body,
    },
  );
  return new Response(await gemini.text(), {
    status: gemini.status,
    headers: { "Content-Type": "application/json" },
  });
});
