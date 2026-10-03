// lily-edge: 온프레미스 앱 공개 주소 {app}.{zone} 앞에서 돈다 (EdgeWorker 가 앱마다 라우트를 건다).
//
// 요청은 원래 오리진(CNAME 내용물: 평소 PC 터널, 거점이 클라우드면 ALB)으로 그대로 보낸다.
// PC 가 응답하지 못하면 같은 요청을 {app}-cloud.{zone}(ALB → 클라우드 대기 Pod)으로 다시 보낸다.
//   - 530(터널 커넥터 없음)·연결 실패: PC 가 요청을 받지 못했다. 모든 메서드를 다시 보낸다
//   - 엣지가 만든 502/503/504/52x 오류 페이지(터널은 붙어 있는데 PC 가 멈춤): PC 가 받았을 수 있어 GET/HEAD/OPTIONS 만 다시 보낸다
//   - GET/HEAD/OPTIONS 는 PC 가 PC_TIMEOUT_MILLIS 안에 응답 헤더를 주지 않으면 끊고 다시 보낸다.
//     PC 가 전원이 꺼지면 터널이 끊긴 줄 모르는 엣지가 응답을 기다리다 6초 넘게 걸려서야 502 를 낸다
// 앱이 돌려준 5xx 는 엣지 오류 페이지가 아니므로 그대로 돌려준다.
//
// PC 가 응답하지 못하면 DOWN_MILLIS 동안 PC 를 건너뛴다. isolate 메모리와 Cache API(같은 데이터센터의 모든 isolate 가 본다)에 둔다.
//
// 읽기 사본: PC 가 공개 GET 에 200 을 주면 그 응답을 이 데이터센터의 Cache API 에 둔다 (SNAPSHOT_SECONDS).
// PC 가 받지 못한 GET/HEAD 를 클라우드도 처리하지 못하면(대기 Pod 없음, DB 가 PC 에 있어 5xx) 사본으로 200, 없으면 503.
//   - 다른 방문자에게 줘도 되는 응답만 둔다: 쿠키·인증 없는 요청, Set-Cookie·private·no-store 없는 응답,
//     Vary 는 Accept-Encoding 만, 허용한 query 만 (토큰이 든 URL 은 두지 않는다)
//   - 앱이 다른 주소로 응답을 만들게 하는 헤더(X-Forwarded-Host 등)가 붙은 요청은 두지 않는다 (사본 오염)
//   - PC 를 거친 PUT/PATCH/DELETE 가 2xx 면 그 경로의 사본을 지운다 (이 데이터센터만)
//   - 사본 저장·조회가 실패해도 원래 응답에는 영향이 없다

/** PC 가 받지 못한 뒤 이 시간 동안은 PC 를 건너뛰고 바로 클라우드로 보낸다 */
const DOWN_MILLIS = 10_000;
/** GET/HEAD/OPTIONS 가 PC 응답 헤더를 기다리는 시간. 넘으면 클라우드로 다시 보낸다 */
const PC_TIMEOUT_MILLIS = 1_500;
/** Cache API 키 경로. 실제 요청 경로와 겹치지 않게 둔다 */
const DOWN_PATH = "/__lily_edge/pc-down";
/** 다시 보내려고 메모리에 들고 있을 본문 크기 상한. 넘거나 길이를 모르면 다시 보내지 않는다 */
const REPLAY_LIMIT = 1024 * 1024;
const CLOUD_SUFFIX = "-cloud";
/** 엣지가 오리진에 닿지 못했을 때 내는 상태 코드. 본문이 Cloudflare 오류 페이지일 때만 PC 장애로 본다 */
const EDGE_ERRORS = new Set([502, 503, 504, 520, 521, 522, 523, 524, 530]);
const SAFE = new Set(["GET", "HEAD", "OPTIONS"]);
/** PC 장애 때 읽기 사본으로 답하는 메서드 */
const READS = new Set(["GET", "HEAD"]);
/** 성공하면 그 경로의 읽기 사본을 지우는 메서드 */
const CHANGES = new Set(["PUT", "PATCH", "DELETE"]);

/** 읽기 사본 보관 시간 (7일). 데이터센터마다 따로 두고, 그전에 밀려날 수 있다 */
const SNAPSHOT_SECONDS = 7 * 24 * 3600;
/** 읽기 사본 Cache API 키 경로. 실제 요청 경로와 겹치지 않게 둔다 */
const SNAPSHOT_PATH = "/__lily_edge/snap";
const SNAPSHOT_MAX_BYTES = 10 * 1024 * 1024;
const SNAPSHOT_TYPES = /^(text\/(html|css|plain|javascript)|application\/(json|javascript|xml|manifest\+json)|image\/|font\/)/;
/** 사본 키에 남기는 query. 이 밖의 query 가 붙으면 저장도 조회도 하지 않는다 */
const SNAPSHOT_QUERY = new Set(["page", "p", "sort", "order", "category", "tag", "q", "lang", "size", "limit", "offset",
  "v", "ver", "version"]);
/** 내용을 바꾸지 않는 추적 파라미터. 사본 키에서 뺀다 */
const TRACKING_QUERY = /^(utm_[a-z_]+|fbclid|gclid|msclkid)$/;
/** 앱이 응답 속 주소를 바꾸게 할 수 있는 요청 헤더 */
const REWRITING_HEADERS = ["x-forwarded-host", "x-forwarded-server", "x-host", "x-original-url", "x-rewrite-url", "forwarded"];
/** 사본에 남기지 않는 응답 헤더. 본문은 압축이 풀린 채 저장된다 */
const SNAPSHOT_DROPPED = ["content-encoding", "content-length", "set-cookie", "vary", "age"];
/** 사본도 클라우드도 없을 때 다시 시도하라고 알리는 초 */
const RETRY_SECONDS = 30;

const down = new Map();

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const cloud = cloudUrl(url);
    if (cloud === null) {
      return fetch(request);
    }
    const host = url.hostname;
    if (await isDown(host)) {
      return fallback(request, null, url, cloud, host);
    }

    const body = await replayableBody(request);
    if (body === undefined) {
      // 다시 보낼 수 없는 요청 (큰 본문, 길이 모르는 스트림). 원래 오리진으로만 보낸다
      return fetch(request);
    }
    let response;
    try {
      response = await fetchPc(request, url, body);
    } catch (e) {
      // 오리진에 연결하지 못했거나 PC 가 제시간에 응답하지 않았다
      markDown(host, ctx);
      return fallback(request, body, url, cloud, host);
    }
    if (!(await edgeFailed(response))) {
      remember(request, url, response, ctx);
      return response;
    }
    markDown(host, ctx);
    if (response.status !== 530 && !SAFE.has(request.method)) {
      // PC 가 받았을 수 있다. 두 번 처리되지 않게 오류를 그대로 돌려준다 (다음 요청부터는 클라우드로)
      return response;
    }
    return fallback(request, body, url, cloud, host);
  },
};

/**
 * PC 가 받지 못한 요청. 클라우드 대기 Pod 로 보내고, GET/HEAD 를 클라우드도 처리하지 못하면(5xx, 연결 실패)
 * 이 데이터센터의 읽기 사본으로 답한다. 사본이 없으면 클라우드 응답을 그대로 주되, 엣지가 만든 오류면 503 으로 바꾼다
 */
async function fallback(request, body, url, cloud, host) {
  const response = await toCloud(request, body, cloud, host);
  if (!READS.has(request.method) || response.status < 500) {
    return response;
  }
  const copy = await snapshot(request, url);
  if (copy !== null) {
    return copy;
  }
  return (await edgeFailed(response)) ? unavailable() : response;
}

/** PC 가 정상으로 답했다. 공개 GET 응답은 읽기 사본으로 두고, 성공한 PUT/PATCH/DELETE 는 그 경로의 사본을 지운다 */
function remember(request, url, response, ctx) {
  try {
    if (CHANGES.has(request.method)) {
      if (response.status >= 200 && response.status < 300) {
        const bare = new URL(url);
        bare.search = "";
        const key = snapshotKey(bare);
        if (key !== null) {
          ctx.waitUntil(caches.default.delete(key).catch(() => false));
        }
      }
      return;
    }
    const key = snapshotKey(url);
    if (key === null || !storable(request, response)) {
      return;
    }
    const headers = new Headers(response.headers);
    for (const name of SNAPSHOT_DROPPED) {
      headers.delete(name);
    }
    headers.set("Cache-Control", "public, max-age=" + SNAPSHOT_SECONDS);
    headers.set("X-Lily-Snapshot-At", new Date().toUTCString());
    const copy = new Response(response.clone().body, { status: 200, headers });
    ctx.waitUntil(caches.default.put(key, copy).catch(() => undefined));
  } catch (e) {
    // 사본은 덤이다. 원래 응답은 그대로 나간다
  }
}

/** 다른 방문자에게 줘도 되는 공개 GET 응답인가 */
function storable(request, response) {
  if (request.method !== "GET" || response.status !== 200) {
    return false;
  }
  const asked = request.headers;
  if (asked.has("cookie") || asked.has("authorization") || asked.has("upgrade")
      || REWRITING_HEADERS.some((name) => asked.has(name))) {
    return false;
  }
  const given = response.headers;
  if (given.has("set-cookie")) {
    return false;
  }
  if (/(^|[\s,])(private|no-store)([\s,=]|$)/.test((given.get("cache-control") ?? "").toLowerCase())) {
    return false;
  }
  const vary = (given.get("vary") ?? "").toLowerCase().split(",").map((name) => name.trim()).filter(Boolean);
  if (vary.some((name) => name !== "accept-encoding")) {
    return false;
  }
  if (!SNAPSHOT_TYPES.test((given.get("content-type") ?? "").toLowerCase())) {
    return false;
  }
  const length = given.get("content-length");
  return length === null || Number(length) <= SNAPSHOT_MAX_BYTES;
}

/** 이 데이터센터의 읽기 사본. 없거나 읽지 못하면 null */
async function snapshot(request, url) {
  const key = snapshotKey(url);
  if (key === null) {
    return null;
  }
  let cached;
  try {
    cached = await caches.default.match(key);
  } catch (e) {
    return null;
  }
  if (cached === undefined) {
    return null;
  }
  const headers = new Headers(cached.headers);
  headers.set("Cache-Control", "no-store");
  headers.set("X-Lily-Edge", "snapshot");
  return new Response(request.method === "HEAD" ? null : cached.body, { status: 200, headers });
}

/**
 * 읽기 사본 키 https://{host}/__lily_edge/snap{path}?{허용 query, 이름순}. 추적 파라미터는 뺀다.
 * 허용하지 않는 query 가 있거나 Worker 내부 경로면 null (저장도 조회도 하지 않는다)
 */
function snapshotKey(url) {
  if (url.pathname.startsWith("/__lily_edge/")) {
    return null;
  }
  const kept = [];
  for (const [name, value] of url.searchParams) {
    if (TRACKING_QUERY.test(name)) {
      continue;
    }
    if (!SNAPSHOT_QUERY.has(name)) {
      return null;
    }
    kept.push([name, value]);
  }
  kept.sort((a, b) => (a[0] === b[0] ? (a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0) : a[0] < b[0] ? -1 : 1));
  const key = new URL("https://" + url.hostname + SNAPSHOT_PATH + url.pathname);
  for (const [name, value] of kept) {
    key.searchParams.append(name, value);
  }
  return key.toString();
}

function unavailable() {
  return new Response("origin unavailable", {
    status: 503,
    headers: { "Retry-After": String(RETRY_SECONDS), "Cache-Control": "no-store", "X-Lily-Edge": "unavailable" },
  });
}

/** PC 로 보낸다. 다시 보내도 되는 요청은 응답 헤더가 PC_TIMEOUT_MILLIS 안에 오지 않으면 끊는다 (본문 스트리밍은 끊지 않는다) */
async function fetchPc(request, url, body) {
  const forwarded = rebuild(request, url, body);
  if (!timesOut(request)) {
    return fetch(forwarded);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), PC_TIMEOUT_MILLIS);
  try {
    return await fetch(forwarded, { signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

/** SSE·WebSocket 은 응답이 늦게 시작해도 정상이라 시간 제한을 걸지 않는다 */
function timesOut(request) {
  return SAFE.has(request.method)
    && request.headers.get("upgrade") === null
    && !(request.headers.get("accept") ?? "").includes("text/event-stream");
}

async function isDown(host) {
  if ((down.get(host) ?? 0) > Date.now()) {
    return true;
  }
  const marked = await caches.default.match(downKey(host));
  if (marked === undefined) {
    return false;
  }
  const until = Number(await marked.text());
  if (until > Date.now()) {
    down.set(host, until);
    return true;
  }
  return false;
}

function markDown(host, ctx) {
  const until = Date.now() + DOWN_MILLIS;
  down.set(host, until);
  const marker = new Response(String(until), {
    headers: { "Cache-Control": "max-age=" + Math.ceil(DOWN_MILLIS / 1000) },
  });
  ctx.waitUntil(caches.default.put(downKey(host), marker));
}

function downKey(host) {
  return "https://" + host + DOWN_PATH;
}

/** 앱이 아니라 엣지가 만든 응답이다 (530, 또는 Cloudflare 오류 페이지) */
async function edgeFailed(response) {
  if (response.status === 530) {
    return true;
  }
  if (!EDGE_ERRORS.has(response.status) || !(response.headers.get("content-type") ?? "").includes("text/html")) {
    return false;
  }
  const text = await response.clone().text();
  return text.includes("/cdn-cgi/");
}

/** {app}.{zone} → {app}-cloud.{zone}. 이미 클라우드 주소면 null */
function cloudUrl(url) {
  const dot = url.hostname.indexOf(".");
  if (dot < 1 || url.hostname.slice(0, dot).endsWith(CLOUD_SUFFIX)) {
    return null;
  }
  const cloud = new URL(url);
  cloud.hostname = url.hostname.slice(0, dot) + CLOUD_SUFFIX + url.hostname.slice(dot);
  return cloud;
}

async function toCloud(request, body, cloud, host) {
  if (body === null && hasBody(request)) {
    body = await replayableBody(request);
    if (body === undefined) {
      return new Response("origin unavailable", { status: 502 });
    }
  }
  const forwarded = rebuild(request, cloud, body);
  forwarded.headers.set("X-Lily-Original-Host", host);
  try {
    const response = await fetch(forwarded);
    const headers = new Headers(response.headers);
    headers.set("X-Lily-Edge", "cloud");
    return new Response(response.body, { status: response.status, statusText: response.statusText, headers, webSocket: response.webSocket });
  } catch (e) {
    // 클라우드 주소가 없거나(대기 배포 없는 앱) 닿지 않는다
    return unavailable();
  }
}

/** 본문이 없으면 null, 상한 안이면 ArrayBuffer, 다시 보낼 수 없으면 undefined */
async function replayableBody(request) {
  if (!hasBody(request)) {
    return null;
  }
  const length = Number(request.headers.get("content-length"));
  if (!Number.isFinite(length) || length <= 0 || length > REPLAY_LIMIT) {
    return undefined;
  }
  return request.arrayBuffer();
}

function hasBody(request) {
  return request.body !== null && request.method !== "GET" && request.method !== "HEAD";
}

function rebuild(request, url, body) {
  return new Request(url, {
    method: request.method,
    headers: new Headers(request.headers),
    body,
    redirect: "manual",
  });
}
