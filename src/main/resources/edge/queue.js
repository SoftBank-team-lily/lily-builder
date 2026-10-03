// lily-edge 쓰기 큐: PC 장애 때 앱 주인이 등록한 경로의 POST 를 앱마다 하나인 Durable Object(WriteQueue)에 쌓고,
// PC 가 돌아오면 받은 순서대로 PC 에 다시 보낸다.
//
//   - 등록 경로는 앱의 DO 에 둔다 (builder 가 관리 주소 QUEUE_ADMIN_HOST 로 바꾼다). Worker 는 앱마다 CONFIG_MILLIS 동안 기억한다
//   - 등록 경로의 POST 는 평소에도 DO 를 거친다. DO 가 요청을 하나씩 처리하므로 재전송 중에 들어온 POST 도 큐 뒤에 붙는다
//   - 큐가 비어 있고 PC 장애 표시가 없으면 DO 가 바로 PC 로 보내고 PC 응답을 그대로 돌려준다
//   - PC 가 받지 못했으면(530·연결 실패, 또는 이 데이터센터가 PC 를 장애로 표시) 큐에 넣고 202 와 X-Lily-Queued-Id 를 돌려준다
//   - PC 에 보낸 뒤 엣지 오류가 나면 PC 가 받았을 수 있어 큐에 넣지 않고 오류를 그대로 돌려준다
//   - 재전송: alarm 이 PC 에 닿는지 확인하고 순번대로 보낸다. 2xx·4xx 는 끝, 5xx·엣지 오류·시간 초과는 멈추고 다시 예약한다
//   - 결과 조회: GET /__lily_edge/queued/{id}
//
// 쌓아 둔 헤더(쿠키·인증 포함)와 본문은 QUEUE_KEY(AES-GCM)로 암호화한다.
// DO 가 PC 로 보내는 요청은 공개 주소를 거치므로 Worker 를 다시 탈 수 있다. QUEUE_KEY 로 만든 표시(X-Lily-Edge-Queue)를 붙여
// Worker 가 큐에 다시 넣지 않고 오리진으로만 보내게 한다.

/** 앱 하나에 쌓아 둘 요청 수 상한. 넘으면 503 */
const QUEUE_LIMIT = 1000;
/** 큐에 넣을 본문 크기 상한 (worker.js 의 REPLAY_LIMIT 와 같다) */
const BODY_LIMIT = 1024 * 1024;
const RETRY_MIN_MILLIS = 10_000;
const RETRY_MAX_MILLIS = 60_000;
/** 재전송 전에 PC 에 닿는지 보는 GET 의 응답 헤더 대기 시간 */
const PROBE_TIMEOUT_MILLIS = 1_500;
/** PC 확인이 앱의 5xx 로 이만큼 이어지면 재전송을 해 본다 (앱의 / 가 늘 5xx 여도 큐가 멈추지 않게) */
const PROBE_APP_ERRORS = 10;
/** 재전송 하나의 응답 헤더 대기 시간. 넘으면 PC 가 받았는지 모르므로 같은 Idempotency-Key 로 다음 alarm 에 다시 보낸다 */
const SEND_TIMEOUT_MILLIS = 10_000;
/** 끝난(sent·failed) 행을 남겨 두는 기간 */
const KEEP_MILLIS = 7 * 24 * 60 * 60 * 1000;
/** Worker 가 앱의 등록 경로를 기억하는 시간. 바꾼 경로는 다른 isolate 에 이만큼 늦게 닿는다 */
const CONFIG_MILLIS = 30_000;
/** 화면에 보일 최근 요청 수 */
const RECENT_ITEMS = 50;
const PATH_LIMIT = 20;

export const STATUS_PATH = "/__lily_edge/queued/";
/** DO 가 PC 로 보내는 요청 표시. Worker 는 이 표시가 맞으면 큐를 건너뛰고 오리진으로만 보낸다 */
const BYPASS_HEADER = "X-Lily-Edge-Queue";
/** 재전송한 요청. 앱이 원래 받은 시각(X-Lily-Received-At)과 함께 본다 */
const REPLAY_HEADER = "X-Lily-Replay";
/** DO 가 PC 장애를 알게 됐다. Worker 가 이 데이터센터에 PC 장애를 표시한다 */
const DOWN_HEADER = "X-Lily-Pc-Down";
const TARGET_HEADER = "X-Lily-Target";

/** 쌓을 때 버리는 헤더. 다시 보낼 때 Cloudflare 가 새로 붙이거나 본문 길이와 함께 다시 정해진다 */
const DROPPED = new Set([
  "host", "content-length", "connection", "keep-alive", "transfer-encoding", "upgrade", "te", "trailer", "cdn-loop",
  BYPASS_HEADER.toLowerCase(), DOWN_HEADER.toLowerCase(), TARGET_HEADER.toLowerCase(),
]);
const EDGE_ERRORS = new Set([502, 503, 504, 520, 521, 522, 523, 524, 530]);

/**
 * worker.js 가 PC 로 보내기 전에 부른다. 큐가 맡지 않는 요청이면 null 이다.
 *
 * @param isDown   이 데이터센터가 PC 를 장애로 표시했는지 (Promise<boolean>)
 * @param markDown DO 가 PC 장애를 알게 됐을 때 이 데이터센터에 표시한다
 */
export async function queued(request, url, env, isDown, markDown) {
  if (!env.QUEUE || !env.QUEUE_KEY) {
    return null;
  }
  const mark = request.headers.get(BYPASS_HEADER);
  if (mark !== null && mark === (await bypassToken(env))) {
    const headers = new Headers(request.headers);
    headers.delete(BYPASS_HEADER);
    return fetch(new Request(request, { headers }));
  }
  if (env.QUEUE_ADMIN_HOST && url.hostname === env.QUEUE_ADMIN_HOST) {
    return admin(request, url, env);
  }
  if (url.pathname.startsWith(STATUS_PATH) && (request.method === "GET" || request.method === "HEAD")) {
    const id = url.pathname.slice(STATUS_PATH.length);
    return queueOf(env, url.hostname).fetch("https://queue/status/" + encodeURIComponent(id));
  }
  if (request.method !== "POST" || !matches(await pathsOf(env, url.hostname), url.pathname)) {
    return null;
  }
  let body = null;
  if (request.body !== null) {
    const length = Number(request.headers.get("content-length"));
    if (request.headers.get("content-length") === null || !Number.isFinite(length) || length > BODY_LIMIT) {
      // 길이를 모르거나 큰 본문은 쌓지 않는다. 지금처럼 PC 로만 보낸다
      return null;
    }
    body = await request.arrayBuffer();
  }
  const headers = new Headers(request.headers);
  headers.set(TARGET_HEADER, url.href);
  if (await isDown()) {
    headers.set(DOWN_HEADER, "1");
  }
  let response;
  try {
    response = await queueOf(env, url.hostname).fetch("https://queue/submit", { method: "POST", headers, body });
  } catch (e) {
    // DO 에 닿지 못했다. 큐 없이 PC 로 보낸다
    return fetch(new Request(url, { method: "POST", headers: request.headers, body, redirect: "manual" }));
  }
  if (response.headers.get(DOWN_HEADER) === null) {
    return response;
  }
  markDown();
  const out = new Headers(response.headers);
  out.delete(DOWN_HEADER);
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers: out });
}

/** 앱마다 하나인 Durable Object. 요청을 하나씩 처리하고, 쌓인 요청을 alarm 으로 다시 보낸다 */
export class WriteQueue {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
    this.sql = ctx.storage.sql;
    this.chain = Promise.resolve();
    this.retry = RETRY_MIN_MILLIS;
    // origin·path 는 평문이다 (PC 확인 주소, 화면 목록). 쿼리·헤더·본문은 payload 에 암호화한다
    this.sql.exec(`CREATE TABLE IF NOT EXISTS q (
      seq INTEGER PRIMARY KEY AUTOINCREMENT,
      id TEXT NOT NULL UNIQUE,
      received_at INTEGER NOT NULL,
      origin TEXT NOT NULL,
      path TEXT NOT NULL,
      iv BLOB NOT NULL,
      payload BLOB NOT NULL,
      state TEXT NOT NULL,
      attempts INTEGER NOT NULL DEFAULT 0,
      status INTEGER,
      updated_at INTEGER NOT NULL)`);
    this.sql.exec("CREATE TABLE IF NOT EXISTS config (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/submit") {
      return this.serial(() => this.submit(request));
    }
    if (url.pathname.startsWith("/status/")) {
      return this.status(decodeURIComponent(url.pathname.slice("/status/".length)));
    }
    if (url.pathname === "/config" && request.method === "GET") {
      return Response.json({ paths: this.paths() });
    }
    if (url.pathname === "/admin/config" && request.method === "PUT") {
      const { paths } = await request.json();
      this.sql.exec("INSERT INTO config (name, value) VALUES ('paths', ?) "
        + "ON CONFLICT(name) DO UPDATE SET value = excluded.value", JSON.stringify(paths));
      return this.state();
    }
    if (url.pathname === "/admin/state" && request.method === "GET") {
      return this.state();
    }
    if (url.pathname === "/admin/clear" && request.method === "DELETE") {
      return this.serial(async () => {
        this.sql.exec("DELETE FROM q");
        this.sql.exec("DELETE FROM config");
        await this.ctx.storage.deleteAlarm();
        return this.state();
      });
    }
    return new Response("not found", { status: 404 });
  }

  async alarm() {
    await this.serial(() => this.drain());
  }

  /**
   * DO 는 바깥으로 fetch 를 기다리는 동안 다음 요청을 받는다. 순번이 뒤집히지 않게 접수와 재전송을 한 줄로 세운다
   */
  serial(task) {
    const run = this.chain.then(task, task);
    this.chain = run.catch(() => {});
    return run;
  }

  async submit(request) {
    const target = request.headers.get(TARGET_HEADER);
    const down = request.headers.get(DOWN_HEADER) === "1";
    const body = request.body === null ? null : await request.arrayBuffer();
    const headers = kept(request.headers);
    if (down || this.pending() > 0) {
      return this.enqueue(target, headers, body, false);
    }
    let response;
    try {
      response = await fetch(await this.toPc(target, headers, body));
    } catch (e) {
      // PC 에 연결하지 못했다. PC 가 받지 않았으니 쌓는다
      return this.enqueue(target, headers, body, true);
    }
    if (response.status === 530) {
      return this.enqueue(target, headers, body, true);
    }
    if (await edgeFailed(response)) {
      // PC 가 받았을 수 있다. 두 번 처리되지 않게 오류를 그대로 돌려준다 (다음 POST 부터는 쌓는다)
      const out = new Headers(response.headers);
      out.set(DOWN_HEADER, "1");
      return new Response(response.body, { status: response.status, statusText: response.statusText, headers: out });
    }
    return response;
  }

  async enqueue(target, headers, body, discovered) {
    if (this.pending() >= QUEUE_LIMIT) {
      return Response.json({ error: "write queue is full" }, { status: 503, headers: { "Retry-After": "60" } });
    }
    const id = crypto.randomUUID();
    const now = Date.now();
    if (!headers.some(([name]) => name === "idempotency-key")) {
      headers.push(["idempotency-key", id]);
    }
    headers.push(["x-lily-received-at", new Date(now).toISOString()]);
    const sealed = await seal(this.env, id, JSON.stringify({ url: target, headers, body: body === null ? null : toBase64(body) }));
    const url = new URL(target);
    this.sql.exec("INSERT INTO q (id, received_at, origin, path, iv, payload, state, updated_at) "
      + "VALUES (?, ?, ?, ?, ?, ?, 'queued', ?)", id, now, url.origin, url.pathname, sealed.iv, sealed.data, now);
    if ((await this.ctx.storage.getAlarm()) === null) {
      await this.ctx.storage.setAlarm(now + RETRY_MIN_MILLIS);
    }
    const out = new Headers({ "X-Lily-Queued-Id": id, "X-Lily-Edge": "queued", Location: STATUS_PATH + id });
    if (discovered) {
      out.set(DOWN_HEADER, "1");
    }
    return Response.json({ queuedId: id, position: this.pending() }, { status: 202, headers: out });
  }

  async drain() {
    this.sql.exec("DELETE FROM q WHERE state != 'queued' AND updated_at < ?", Date.now() - KEEP_MILLIS);
    const rows = this.sql.exec("SELECT id, origin, iv, payload FROM q WHERE state = 'queued' ORDER BY seq").toArray();
    if (rows.length === 0) {
      this.retry = RETRY_MIN_MILLIS;
      return;
    }
    if (!(await this.ready(rows[0].origin))) {
      return this.later();
    }
    for (const row of rows) {
      let saved;
      try {
        saved = JSON.parse(await open(this.env, row.id, row.iv, row.payload));
      } catch (e) {
        // QUEUE_KEY 가 바뀌어 풀 수 없다. 다시 보낼 수 없으니 실패로 남기고 다음 요청으로 간다
        this.sql.exec("UPDATE q SET state = 'failed', updated_at = ? WHERE id = ?", Date.now(), row.id);
        continue;
      }
      let response;
      try {
        response = await this.send(saved);
      } catch (e) {
        // 시간 초과·연결 끊김. PC 가 받았는지 모르므로 같은 Idempotency-Key 로 다시 보낸다
        this.sql.exec("UPDATE q SET attempts = attempts + 1, updated_at = ? WHERE id = ?", Date.now(), row.id);
        return this.later();
      }
      if (response.status >= 500 || (await edgeFailed(response))) {
        this.sql.exec("UPDATE q SET attempts = attempts + 1, status = ?, updated_at = ? WHERE id = ?",
          response.status, Date.now(), row.id);
        return this.later();
      }
      this.sql.exec("UPDATE q SET state = ?, status = ?, attempts = attempts + 1, updated_at = ? WHERE id = ?",
        response.status < 400 ? "sent" : "failed", response.status, Date.now(), row.id);
    }
    this.retry = RETRY_MIN_MILLIS;
  }

  async later() {
    await this.ctx.storage.setAlarm(Date.now() + this.retry);
    this.retry = Math.min(this.retry * 2, RETRY_MAX_MILLIS);
  }

  /**
   * 다시 보내도 되는지 공개 주소의 / 로 본다. 5xx 면 엣지 오류(PC 에 닿지 않음)든 앱 오류(DB 가 아직 없음)든 기다린다.
   * 앱의 / 가 늘 5xx 인 앱이 영영 멈추지 않게, 엣지 오류가 아닌 5xx 가 PROBE_APP_ERRORS 번 이어지면 보내 본다.
   * 결과는 관리 상태의 lastCheck 로 보인다
   */
  async ready(origin) {
    const check = await this.probe(origin);
    const appErrors = check.status !== null && check.status >= 500 && !check.edge ? this.lastCheck().appErrors + 1 : 0;
    this.sql.exec("INSERT INTO config (name, value) VALUES ('check', ?) "
      + "ON CONFLICT(name) DO UPDATE SET value = excluded.value", JSON.stringify({ at: Date.now(), ...check, appErrors }));
    if (check.status !== null && check.status < 500) {
      return true;
    }
    return appErrors >= PROBE_APP_ERRORS;
  }

  /** @return {status: 응답 코드 (연결 실패·시간 초과면 null), edge: Cloudflare 가 만든 오류인가} */
  async probe(origin) {
    const headers = new Headers({ [BYPASS_HEADER]: await bypassToken(this.env) });
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MILLIS);
    try {
      const response = await fetch(origin + "/", { headers, signal: controller.signal, redirect: "manual" });
      return { status: response.status, edge: await edgeFailed(response) };
    } catch (e) {
      return { status: null, edge: true };
    } finally {
      clearTimeout(timer);
    }
  }

  lastCheck() {
    const row = this.sql.exec("SELECT value FROM config WHERE name = 'check'").toArray()[0];
    return row === undefined ? { at: null, status: null, edge: false, appErrors: 0 } : JSON.parse(row.value);
  }

  async send(saved) {
    const request = await this.toPc(saved.url, saved.headers, saved.body === null ? null : fromBase64(saved.body), true);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), SEND_TIMEOUT_MILLIS);
    try {
      return await fetch(request, { signal: controller.signal });
    } finally {
      clearTimeout(timer);
    }
  }

  async toPc(target, headers, body, replay = false) {
    const out = new Headers(headers);
    out.set(BYPASS_HEADER, await bypassToken(this.env));
    if (replay) {
      out.set(REPLAY_HEADER, "1");
    }
    return new Request(target, { method: "POST", headers: out, body, redirect: "manual" });
  }

  pending() {
    return this.sql.exec("SELECT COUNT(*) AS n FROM q WHERE state = 'queued'").one().n;
  }

  paths() {
    const row = this.sql.exec("SELECT value FROM config WHERE name = 'paths'").toArray()[0];
    return row === undefined ? [] : JSON.parse(row.value);
  }

  /** 관리 화면: 등록 경로, 상태별 건수, 최근 요청 (헤더·본문은 보이지 않는다) */
  state() {
    const counts = { queued: 0, sent: 0, failed: 0 };
    for (const row of this.sql.exec("SELECT state, COUNT(*) AS n FROM q GROUP BY state").toArray()) {
      counts[row.state] = row.n;
    }
    const items = this.sql.exec(
      "SELECT id, path, state, status, attempts, received_at, updated_at FROM q ORDER BY seq DESC LIMIT ?", RECENT_ITEMS,
    ).toArray().map((row) => ({
      queuedId: row.id,
      path: row.path,
      state: row.state,
      status: row.status,
      attempts: row.attempts,
      receivedAt: new Date(row.received_at).toISOString(),
      updatedAt: new Date(row.updated_at).toISOString(),
    }));
    const check = this.lastCheck();
    const lastCheck = check.at === null ? null : { ...check, at: new Date(check.at).toISOString() };
    return Response.json({ paths: this.paths(), counts, items, lastCheck });
  }

  status(id) {
    const row = this.sql.exec("SELECT seq, state, status, received_at, updated_at FROM q WHERE id = ?", id).toArray()[0];
    if (row === undefined) {
      return Response.json({ error: "not found" }, { status: 404 });
    }
    const position = row.state === "queued"
      ? this.sql.exec("SELECT COUNT(*) AS n FROM q WHERE state = 'queued' AND seq <= ?", row.seq).one().n
      : null;
    return Response.json({
      queuedId: id,
      state: row.state,
      status: row.status,
      position,
      receivedAt: new Date(row.received_at).toISOString(),
      updatedAt: new Date(row.updated_at).toISOString(),
    });
  }
}

/**
 * builder 가 부르는 관리 주소. Authorization: Bearer {QUEUE_KEY 에서 만든 관리 토큰}
 *
 *   GET    /apps/{host}/queue          등록 경로, 상태별 건수, 최근 요청
 *   PUT    /apps/{host}/queue/config   {"paths": ["/posts", ...]} 등록 경로를 바꾼다 (빈 목록이면 끈다. 쌓인 요청은 계속 보낸다)
 *   DELETE /apps/{host}/queue          쌓인 요청과 등록 경로를 지운다 (앱 삭제)
 */
async function admin(request, url, env) {
  if (request.headers.get("authorization") !== "Bearer " + (await adminToken(env))) {
    return Response.json({ error: "unauthorized" }, { status: 401 });
  }
  const match = /^\/apps\/([a-z0-9.-]{1,253})\/queue(\/config)?$/.exec(url.pathname);
  if (match === null) {
    return Response.json({ error: "not found" }, { status: 404 });
  }
  const [, host, config] = match;
  const queue = queueOf(env, host);
  if (config && request.method === "PUT") {
    const paths = validPaths((await request.json().catch(() => null))?.paths);
    if (paths === null) {
      return Response.json({ error: "paths must be up to " + PATH_LIMIT + " paths starting with /" }, { status: 400 });
    }
    configs.set(host, { paths, until: Date.now() + CONFIG_MILLIS });
    return queue.fetch("https://queue/admin/config", {
      method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify({ paths }),
    });
  }
  if (!config && request.method === "GET") {
    return queue.fetch("https://queue/admin/state");
  }
  if (!config && request.method === "DELETE") {
    configs.delete(host);
    return queue.fetch("https://queue/admin/clear", { method: "DELETE" });
  }
  return Response.json({ error: "method not allowed" }, { status: 405 });
}

/** 등록 경로 목록. 각 경로는 / 로 시작하고 쿼리·공백이 없다. 맞지 않으면 null */
function validPaths(paths) {
  if (!Array.isArray(paths) || paths.length > PATH_LIMIT) {
    return null;
  }
  const out = [];
  for (const path of paths) {
    if (typeof path !== "string" || !/^\/[^?#\s]{0,199}$/.test(path)) {
      return null;
    }
    if (!out.includes(path)) {
      out.push(path);
    }
  }
  return out;
}

/** 요청 경로가 등록 경로와 같거나 그 아래다 (/posts 는 /posts, /posts/3/comments 를 맡고 /postsx 는 맡지 않는다) */
function matches(paths, pathname) {
  return paths.some((path) => pathname === path || pathname.startsWith(path.endsWith("/") ? path : path + "/"));
}

/** host → { paths, until } */
const configs = new Map();

/** 앱의 등록 경로. DO 에 닿지 못하면 잠깐 빈 목록으로 본다 (큐 없이 지금처럼 PC 로 보낸다) */
async function pathsOf(env, host) {
  const known = configs.get(host);
  if (known !== undefined && known.until > Date.now()) {
    return known.paths;
  }
  try {
    const response = await queueOf(env, host).fetch("https://queue/config");
    const paths = (await response.json()).paths ?? [];
    configs.set(host, { paths, until: Date.now() + CONFIG_MILLIS });
    return paths;
  } catch (e) {
    configs.set(host, { paths: [], until: Date.now() + 5_000 });
    return [];
  }
}

function queueOf(env, host) {
  return env.QUEUE.get(env.QUEUE.idFromName(host));
}

/** 쌓아 둘 헤더 [이름, 값] (소문자 이름). cf-* 와 연결 헤더는 버린다 */
function kept(headers) {
  const out = [];
  for (const [name, value] of headers) {
    if (!DROPPED.has(name) && !name.startsWith("cf-")) {
      out.push([name, value]);
    }
  }
  return out;
}

/**
 * 앱이 아니라 엣지가 만든 응답이다. 브라우저에는 /cdn-cgi/ 가 든 HTML 오류 페이지가 가고,
 * Worker·DO 의 하위 요청에는 "error code: 1033" 같은 짧은 텍스트가 온다
 */
async function edgeFailed(response) {
  if (response.status === 530) {
    return true;
  }
  const type = response.headers.get("content-type") ?? "";
  if (!EDGE_ERRORS.has(response.status) || !(type === "" || type.includes("text/html") || type.includes("text/plain"))) {
    return false;
  }
  const text = await response.clone().text();
  return text.includes("/cdn-cgi/") || /^error code: \d+/i.test(text.trim());
}

const tokens = new Map();

/** QUEUE_KEY 에서 만든 큐 건너뛰기 표시. 키를 모르면 만들 수 없다 */
function bypassToken(env) {
  return derived("lily-edge-queue:" + env.QUEUE_KEY);
}

/** QUEUE_KEY 에서 만든 관리 토큰. builder 의 EdgeQueue 가 같은 방법으로 만든다 */
function adminToken(env) {
  return derived("lily-edge-admin:" + env.QUEUE_KEY);
}

/** SHA-256 hex */
async function derived(text) {
  let token = tokens.get(text);
  if (token === undefined) {
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
    token = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
    tokens.set(text, token);
  }
  return token;
}

async function aesKey(env) {
  return crypto.subtle.importKey("raw", fromBase64(env.QUEUE_KEY), "AES-GCM", false, ["encrypt", "decrypt"]);
}

/** 행 id 를 추가 인증 데이터로 묶어, 암호문을 다른 행으로 옮기면 풀리지 않게 한다 */
async function seal(env, id, text) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const data = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: new TextEncoder().encode(id) },
    await aesKey(env), new TextEncoder().encode(text));
  return { iv: iv.buffer, data };
}

async function open(env, id, iv, data) {
  const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv, additionalData: new TextEncoder().encode(id) },
    await aesKey(env), data);
  return new TextDecoder().decode(plain);
}

function toBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let text = "";
  for (let i = 0; i < bytes.length; i += 0x8000) {
    text += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(text);
}

function fromBase64(text) {
  const raw = atob(text);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) {
    bytes[i] = raw.charCodeAt(i);
  }
  return bytes.buffer;
}
