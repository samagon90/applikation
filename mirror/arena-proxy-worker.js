// ============================================================================
// Arena AI — бесплатное зеркало (Cloudflare Worker)
// ============================================================================
// Прозрачно проксирует https://arena.ai через инфраструктуру Cloudflare:
// запросы к вашему worker-домену уходят на arena.ai с IP Cloudflare,
// а не с IP пользователя — поэтому сайт открывается даже там,
// где arena.ai заблокирован (например, в России).
//
// Поддерживается: обычный трафик, WebSocket и SSE (стриминг ответов моделей),
// cookie (авторизация), редиректы и абсолютные ссылки в HTML.
//
// Бесплатный тариф Workers: 100 000 запросов/день — с запасом.
//
// Как развернуть: см. mirror/README.md
// ============================================================================

const UPSTREAM_HOST = "arena.ai";

export default {
  async fetch(request) {
    const originalUrl = new URL(request.url);
    const mirrorHost = originalUrl.host;

    // --- Переписываем запрос на оригинальный хост --------------------------
    const upstreamUrl = new URL(request.url);
    upstreamUrl.hostname = UPSTREAM_HOST;
    upstreamUrl.protocol = "https:";

    const headers = new Headers(request.headers);
    headers.set("Host", UPSTREAM_HOST);
    headers.set("Origin", `https://${UPSTREAM_HOST}`);
    if (request.headers.get("referer")) {
      try {
        const ref = new URL(request.headers.get("referer"));
        ref.hostname = UPSTREAM_HOST;
        ref.protocol = "https:";
        headers.set("Referer", ref.toString());
      } catch (_) {
        headers.set("Referer", `https://${UPSTREAM_HOST}/`);
      }
    }
    // Не отдаём оригиналу адрес вашего зеркала как путь запроса — он и так
    // совпадает. Убираем служебные заголовки Cloudflare.
    headers.delete("cf-connecting-ip");
    headers.delete("cf-ipcountry");
    headers.delete("cf-ray");
    headers.delete("cf-visitor");

    const init = {
      method: request.method,
      headers: headers,
      redirect: "manual",
    };
    if (request.method !== "GET" && request.method !== "HEAD") {
      init.body = request.body;
    }

    // --- Запрос к оригиналу (WebSocket-upgrade проходит насквозь) -----------
    const upstreamRequest = new Request(upstreamUrl, init);
    let response = await fetch(upstreamRequest);

    // --- Переписываем абсолютные ссылки arena.ai -> домен зеркала -----------
    const contentType = response.headers.get("content-type") || "";
    if (contentType.includes("text/html") || contentType.includes("application/javascript")) {
      const body = await response.text();
      const rewritten = body
        .split(`https://${UPSTREAM_HOST}`).join(`https://${mirrorHost}`)
        .split(`http://${UPSTREAM_HOST}`).join(`https://${mirrorHost}`)
        .split(`wss://${UPSTREAM_HOST}`).join(`wss://${mirrorHost}`)
        .split(`ws://${UPSTREAM_HOST}`).join(`wss://${mirrorHost}`);
      response = new Response(rewritten, response);
    }

    // --- Cookie: убираем Domain=arena.ai, чтобы авторизация жила на зеркале --
    const setCookies = response.headers.get("set-cookie");
    if (setCookies) {
      const cleaned = setCookies
        .split(/(?<=,)\s/)
        .map((cookie) => cookie.replace(/\s*Domain=\.?arena\.ai\s*;?/gi, ""))
        .join("");
      response.headers.set("set-cookie", cleaned);
    }

    // --- Редиректы (302) — тоже на зеркало --------------------------------
    const location = response.headers.get("location");
    if (location && /^https?:\/\/arena\.ai/i.test(location)) {
      response.headers.set(
        "location",
        location.replace(/^https?:\/\/arena\.ai/i, `https://${mirrorHost}`)
      );
    }

    return response;
  },
};
