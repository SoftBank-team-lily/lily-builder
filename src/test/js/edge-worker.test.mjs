// lily-edge Worker (src/main/resources/edge/worker.js) 테스트. 실행: node --test src/test/js (gradlew edgeTest)
//
// Cloudflare 런타임 대신 Node 의 fetch API 를 쓰고, caches.default 와 오리진 fetch 는 가짜로 둔다.
// worker.js 는 PC 장애 표시를 isolate 메모리에 두므로 테스트마다 다른 앱 이름(host)을 쓴다.

import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";

const worker = (await import("../../main/resources/edge/worker.js")).default;

const ZONE = "lilycloud.kr";

/** caches.default: URL 문자열 → {status, headers, body} */
class FakeCache {
  constructor() {
    this.entries = new Map();
  }
  async put(key, response) {
    const body = await response.arrayBuffer();
    this.entries.set(String(key), { status: response.status, headers: new Headers(response.headers), body });
  }
  async match(key) {
    const found = this.entries.get(String(key instanceof Request ? key.url : key));
    return found ? new Response(found.body, { status: found.status, headers: found.headers }) : undefined;
  }
  async delete(key) {
    return this.entries.delete(String(key));
  }
  snapshots() {
    return [...this.entries.keys()].filter((key) => key.includes("/__lily_edge/snap"));
  }
}

let cache;
/** host → (Request) => Response | throw */
let origins;

beforeEach(() => {
  cache = new FakeCache();
  globalThis.caches = { default: cache };
  origins = new Map();
  globalThis.fetch = async (request, init) => {
    const handler = origins.get(new URL(request.url).hostname);
    if (!handler) {
      throw new TypeError("fetch failed: no origin for " + request.url);
    }
    return handler(init?.signal ? new Request(request, { signal: init.signal }) : request);
  };
});

/** 요청 하나를 Worker 에 보내고 waitUntil 로 미룬 일(사본 저장·삭제)까지 끝낸다 */
async function send(url, init = {}, env = {}) {
  const pending = [];
  const ctx = { waitUntil: (promise) => pending.push(promise) };
  const response = await worker.fetch(new Request(url, init), env, ctx);
  const body = init.method === "HEAD" ? "" : await response.text();
  await Promise.all(pending);
  return { status: response.status, headers: response.headers, body };
}

function html(body, headers = {}) {
  return new Response(body, { status: 200, headers: { "content-type": "text/html; charset=utf-8", ...headers } });
}

function pcServes(app, handler) {
  origins.set(`${app}.${ZONE}`, handler);
}

function pcDies(app) {
  origins.set(`${app}.${ZONE}`, () => {
    throw new TypeError("fetch failed: tunnel down");
  });
}

function cloudServes(app, handler) {
  origins.set(`${app}-cloud.${ZONE}`, handler);
}

test("PC가 공개 GET에 200을 주면 사본을 저장하고, PC가 죽고 대기 Pod가 없으면 그 사본을 200 snapshot으로 준다", async () => {
  pcServes("blog-a", () => html("<h1>posts</h1>"));
  const first = await send(`https://blog-a.${ZONE}/posts/3`);
  assert.equal(first.status, 200);
  assert.equal(first.body, "<h1>posts</h1>");

  pcDies("blog-a");
  const down = await send(`https://blog-a.${ZONE}/posts/3`);

  assert.equal(down.status, 200);
  assert.equal(down.body, "<h1>posts</h1>");
  assert.equal(down.headers.get("x-lily-edge"), "snapshot");
  assert.equal(down.headers.get("cache-control"), "no-store");
  assert.ok(down.headers.get("x-lily-snapshot-at"));
});

test("PC가 죽고 클라우드 대기 Pod가 DB 연결 실패로 500을 주면 사본을 200으로 준다", async () => {
  pcServes("blog-b", () => html("<h1>list</h1>"));
  await send(`https://blog-b.${ZONE}/`);

  pcDies("blog-b");
  cloudServes("blog-b", () => new Response("db down", { status: 500 }));
  const down = await send(`https://blog-b.${ZONE}/`);

  assert.equal(down.status, 200);
  assert.equal(down.body, "<h1>list</h1>");
  assert.equal(down.headers.get("x-lily-edge"), "snapshot");
});

/** signal 이 끊길 때까지 응답 헤더를 주지 않는 오리진 (역방향 터널로 죽은 PC DB 를 기다리는 대기 Pod) */
function hangs(request) {
  return new Promise((resolve, reject) => {
    request.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
  });
}

test("PC가 죽고 대기 Pod가 응답 헤더를 3초 안에 주지 않으면 사본을 주고, 이후 10초 동안은 대기 Pod를 기다리지 않는다", async () => {
  pcServes("blog-s", () => html("<p>list</p>"));
  await send(`https://blog-s.${ZONE}/api/posts`);

  pcDies("blog-s");
  let cloudCalls = 0;
  cloudServes("blog-s", (request) => {
    cloudCalls++;
    return hangs(request);
  });
  const started = Date.now();
  const first = await send(`https://blog-s.${ZONE}/api/posts`);
  const firstMillis = Date.now() - started;
  const again = Date.now();
  const second = await send(`https://blog-s.${ZONE}/api/posts`);

  assert.equal(first.status, 200);
  assert.equal(first.headers.get("x-lily-edge"), "snapshot");
  assert.ok(firstMillis >= 3000 && firstMillis < 4500, `first ${firstMillis}ms`);
  assert.equal(second.status, 200);
  assert.ok(Date.now() - again < 500, "second waited for cloud");
  assert.equal(cloudCalls, 1);
});

test("PC가 죽었는데 사본이 없으면 대기 Pod 응답을 3초가 넘어도 기다린다", async () => {
  pcDies("blog-t");
  cloudServes("blog-t", () => new Promise((resolve) => setTimeout(() => resolve(html("<p>cold start</p>")), 3300)));

  const down = await send(`https://blog-t.${ZONE}/`);

  assert.equal(down.status, 200);
  assert.equal(down.body, "<p>cold start</p>");
  assert.equal(down.headers.get("x-lily-edge"), "cloud");
});

test("PC가 죽고 클라우드 대기 Pod가 200을 주면 사본 대신 클라우드 응답을 준다", async () => {
  pcServes("blog-c", () => html("<h1>old</h1>"));
  await send(`https://blog-c.${ZONE}/`);

  pcDies("blog-c");
  cloudServes("blog-c", () => html("<h1>cloud</h1>"));
  const down = await send(`https://blog-c.${ZONE}/`);

  assert.equal(down.status, 200);
  assert.equal(down.body, "<h1>cloud</h1>");
  assert.equal(down.headers.get("x-lily-edge"), "cloud");
});

test("PC가 죽었는데 그 경로의 사본이 없으면 503과 Retry-After를 준다", async () => {
  pcDies("blog-d");

  const down = await send(`https://blog-d.${ZONE}/posts/99`);

  assert.equal(down.status, 503);
  assert.equal(down.headers.get("retry-after"), "30");
});

test("쿠키가 있는 요청의 응답은 사본으로 저장하지 않는다", async () => {
  pcServes("blog-e", () => html("<p>hello alice</p>"));

  await send(`https://blog-e.${ZONE}/mypage`, { headers: { cookie: "SESSION=abc" } });

  assert.deepEqual(cache.snapshots(), []);
});

test("Authorization 헤더가 있는 요청의 응답은 사본으로 저장하지 않는다", async () => {
  pcServes("blog-f", () => new Response("{}", { headers: { "content-type": "application/json" } }));

  await send(`https://blog-f.${ZONE}/api/me`, { headers: { authorization: "Bearer t" } });

  assert.deepEqual(cache.snapshots(), []);
});

test("Set-Cookie가 붙은 응답은 사본으로 저장하지 않는다", async () => {
  pcServes("blog-g", () => html("<p>welcome</p>", { "set-cookie": "SESSION=new" }));

  await send(`https://blog-g.${ZONE}/`);

  assert.deepEqual(cache.snapshots(), []);
});

test("Cache-Control이 private이나 no-store인 응답은 사본으로 저장하지 않는다", async () => {
  pcServes("blog-h", (request) => html("<p>x</p>", {
    "cache-control": new URL(request.url).pathname === "/a" ? "private, max-age=60" : "no-store",
  }));

  await send(`https://blog-h.${ZONE}/a`);
  await send(`https://blog-h.${ZONE}/b`);

  assert.deepEqual(cache.snapshots(), []);
});

test("Vary에 Accept-Encoding 말고 다른 헤더가 있는 응답은 사본으로 저장하지 않는다", async () => {
  pcServes("blog-i", () => html("<p>ko</p>", { vary: "Accept-Encoding, Accept-Language" }));

  await send(`https://blog-i.${ZONE}/`);

  assert.deepEqual(cache.snapshots(), []);
});

test("200이 아니거나 HTML·JSON·정적 파일이 아닌 응답은 사본으로 저장하지 않는다", async () => {
  pcServes("blog-j", (request) => new URL(request.url).pathname === "/missing"
    ? new Response("nope", { status: 404, headers: { "content-type": "text/html" } })
    : new Response("bin", { headers: { "content-type": "application/octet-stream" } }));

  await send(`https://blog-j.${ZONE}/missing`);
  await send(`https://blog-j.${ZONE}/download`);

  assert.deepEqual(cache.snapshots(), []);
});

test("허용하지 않는 query가 붙은 URL은 저장하지 않고, 장애 중에도 사본으로 답하지 않는다", async () => {
  pcServes("blog-k", () => html("<p>reset form</p>"));
  await send(`https://blog-k.${ZONE}/reset?token=secret`);
  assert.deepEqual(cache.snapshots(), []);

  pcDies("blog-k");
  const down = await send(`https://blog-k.${ZONE}/reset?token=secret`);
  assert.equal(down.status, 503);
});

test("page 같은 허용 query는 순서와 상관없이 같은 사본을 쓰고, utm 추적 파라미터는 키에서 뺀다", async () => {
  pcServes("blog-l", () => html("<p>page 2</p>"));
  await send(`https://blog-l.${ZONE}/posts?sort=new&page=2&utm_source=mail`);

  pcDies("blog-l");
  const down = await send(`https://blog-l.${ZONE}/posts?page=2&sort=new&fbclid=x`);

  assert.equal(down.status, 200);
  assert.equal(down.body, "<p>page 2</p>");
});

test("X-Forwarded-Host가 붙은 요청의 응답은 사본으로 저장하지 않는다", async () => {
  pcServes("blog-m", () => html('<a href="https://evil.example/">home</a>'));

  await send(`https://blog-m.${ZONE}/`, { headers: { "x-forwarded-host": "evil.example" } });

  assert.deepEqual(cache.snapshots(), []);
});

test("PC를 거친 DELETE가 2xx면 그 경로의 사본을 지운다", async () => {
  pcServes("blog-n", (request) => request.method === "DELETE" ? new Response(null, { status: 204 }) : html("<p>post 3</p>"));
  await send(`https://blog-n.${ZONE}/posts/3`);
  assert.equal(cache.snapshots().length, 1);

  await send(`https://blog-n.${ZONE}/posts/3`, { method: "DELETE" });

  assert.deepEqual(cache.snapshots(), []);
});

test("PC가 죽었을 때 POST는 사본으로 답하지 않고 503을 준다", async () => {
  pcServes("blog-o", () => html("<p>posts</p>"));
  await send(`https://blog-o.${ZONE}/posts`);

  pcDies("blog-o");
  const down = await send(`https://blog-o.${ZONE}/posts`, {
    method: "POST", body: "title=hi", headers: { "content-type": "application/x-www-form-urlencoded", "content-length": "8" },
  });

  assert.equal(down.status, 503);
  assert.notEqual(down.headers.get("x-lily-edge"), "snapshot");
});

test("PC가 죽었을 때 쿠키가 있는 GET에도 공개 사본을 준다", async () => {
  pcServes("blog-p", () => html("<p>public</p>"));
  await send(`https://blog-p.${ZONE}/`);

  pcDies("blog-p");
  const down = await send(`https://blog-p.${ZONE}/`, { headers: { cookie: "SESSION=abc" } });

  assert.equal(down.status, 200);
  assert.equal(down.body, "<p>public</p>");
});

test("PC가 죽었을 때 HEAD는 본문 없이 사본의 상태와 헤더를 준다", async () => {
  pcServes("blog-q", () => html("<p>head</p>"));
  await send(`https://blog-q.${ZONE}/`);

  pcDies("blog-q");
  const down = await send(`https://blog-q.${ZONE}/`, { method: "HEAD" });

  assert.equal(down.status, 200);
  assert.equal(down.headers.get("x-lily-edge"), "snapshot");
  assert.match(down.headers.get("content-type"), /text\/html/);
});

test("사본에는 Content-Encoding과 Content-Length를 남기지 않고 7일 보관으로 저장한다", async () => {
  pcServes("blog-r", () => html("<p>gz</p>", { "content-encoding": "gzip", "content-length": "9" }));

  await send(`https://blog-r.${ZONE}/`);

  const stored = cache.entries.get(`https://blog-r.${ZONE}/__lily_edge/snap/`);
  assert.ok(stored);
  assert.equal(stored.headers.get("content-encoding"), null);
  assert.equal(stored.headers.get("content-length"), null);
  assert.equal(stored.headers.get("cache-control"), "public, max-age=604800");
});

/** 쓰기 큐 바인딩. 앱 DO 설정 /config 가 {paths: [], snapshot} 을 준다 (배포 화면의 읽기 사본 체크박스) */
function edgeOptions(snapshot) {
  return {
    QUEUE_KEY: Buffer.alloc(32, 1).toString("base64"),
    QUEUE: { idFromName: (name) => name, get: () => ({ fetch: async () => Response.json({ paths: [], snapshot }) }) },
  };
}

test("읽기_사본을_끈_앱은_PC가_준_공개_GET_200을_저장하지_않고_PC가_죽으면_사본_대신_503을_준다", async () => {
  const env = edgeOptions(false);
  pcServes("blog-off", () => html("<h1>posts</h1>"));
  const first = await send(`https://blog-off.${ZONE}/posts/3`, {}, env);
  assert.equal(first.status, 200);
  assert.deepEqual(cache.snapshots(), []);

  pcDies("blog-off");
  const down = await send(`https://blog-off.${ZONE}/posts/3`, {}, env);

  assert.equal(down.status, 503);
  assert.notEqual(down.headers.get("x-lily-edge"), "snapshot");
});

test("읽기_사본을_켠_앱은_쓰기_큐_바인딩이_있어도_지금처럼_사본을_저장하고_PC가_죽으면_200_snapshot을_준다", async () => {
  const env = edgeOptions(true);
  pcServes("blog-on", () => html("<h1>posts</h1>"));
  await send(`https://blog-on.${ZONE}/posts/3`, {}, env);
  assert.equal(cache.snapshots().length, 1);

  pcDies("blog-on");
  const down = await send(`https://blog-on.${ZONE}/posts/3`, {}, env);

  assert.equal(down.status, 200);
  assert.equal(down.headers.get("x-lily-edge"), "snapshot");
});

// ---- 멀티클라우드 앱 (앱 DO 설정 split = GCP 로 보내는 비율) ----

/** 앱 DO 의 설정만 돌려주는 가짜 QUEUE 바인딩. split 이 null 이면 멀티클라우드가 아니다 */
function multiEnv(split) {
  return {
    QUEUE_KEY: Buffer.alloc(32, 7).toString("base64"),
    QUEUE: {
      idFromName: (name) => name,
      get: () => ({ fetch: async () => Response.json({ paths: [], snapshot: true, split }) }),
    },
  };
}

function cloudOf(app, cloud, handler) {
  origins.set(`${app}-${cloud}.${ZONE}`, handler);
}

function withRandom(value, run) {
  const original = Math.random;
  Math.random = () => value;
  return run().finally(() => {
    Math.random = original;
  });
}

const cdnError = () => new Response("<html>error <a href='/cdn-cgi/l/'>cf</a></html>",
  { status: 502, headers: { "content-type": "text/html" } });

test("멀티클라우드 앱은 비율대로 {app}-gcp 와 {app}-aws 로 나누고 X-Lily-Cloud 를 붙인다", async () => {
  const seen = [];
  cloudOf("multi-a", "gcp", (request) => {
    seen.push(["gcp", request.headers.get("x-lily-original-host")]);
    return new Response("from gcp");
  });
  cloudOf("multi-a", "aws", () => new Response("from aws"));

  const toGcp = await withRandom(0.29, () => send(`https://multi-a.${ZONE}/posts`, {}, multiEnv(30)));
  const toAws = await withRandom(0.31, () => send(`https://multi-a.${ZONE}/posts`, {}, multiEnv(30)));

  assert.equal(toGcp.body, "from gcp");
  assert.equal(toGcp.headers.get("x-lily-cloud"), "gcp");
  assert.equal(toAws.body, "from aws");
  assert.equal(toAws.headers.get("x-lily-cloud"), "aws");
  assert.deepEqual(seen, [["gcp", `multi-a.${ZONE}`]]);
});

test("고른 클라우드에 연결하지 못하면 POST 도 반대쪽으로 보내고, 다음 요청은 그쪽을 건너뛴다", async () => {
  let gcpCalls = 0;
  cloudOf("multi-b", "gcp", () => {
    gcpCalls++;
    throw new TypeError("fetch failed: connection refused");
  });
  cloudOf("multi-b", "aws", async (request) => new Response("aws got " + (await request.text()), { status: 201 }));

  const first = await withRandom(0, () => send(`https://multi-b.${ZONE}/api/posts`,
    { method: "POST", body: "hello", headers: { "content-length": "5" } }, multiEnv(100)));
  const second = await withRandom(0, () => send(`https://multi-b.${ZONE}/api/posts`, {}, multiEnv(100)));

  assert.equal(first.status, 201);
  assert.equal(first.body, "aws got hello");
  assert.equal(first.headers.get("x-lily-cloud"), "aws");
  assert.equal(second.headers.get("x-lily-cloud"), "aws");
  assert.equal(gcpCalls, 1);
});

test("엣지 오류 페이지는 GET 만 반대쪽으로 다시 보내고 POST 는 그대로 돌려준다", async () => {
  cloudOf("multi-c", "gcp", cdnError);
  cloudOf("multi-c", "aws", () => new Response("from aws"));

  const get = await withRandom(0, () => send(`https://multi-c.${ZONE}/`, {}, multiEnv(100)));
  assert.equal(get.body, "from aws");

  cloudOf("multi-d", "gcp", cdnError);
  cloudOf("multi-d", "aws", () => new Response("from aws"));
  const post = await withRandom(0, () => send(`https://multi-d.${ZONE}/api/posts`,
    { method: "POST", body: "x", headers: { "content-length": "1" } }, multiEnv(100)));
  assert.equal(post.status, 502);
  assert.equal(post.headers.get("x-lily-cloud"), "gcp");
});

test("두 클라우드 모두 받지 못하면 503", async () => {
  cloudOf("multi-e", "gcp", () => {
    throw new TypeError("down");
  });
  cloudOf("multi-e", "aws", () => {
    throw new TypeError("down");
  });

  const response = await withRandom(0.5, () => send(`https://multi-e.${ZONE}/`, {}, multiEnv(50)));

  assert.equal(response.status, 503);
});

test("split 이 없는 앱은 지금처럼 PC 오리진으로 보낸다", async () => {
  pcServes("single-a", () => html("pc"));
  cloudOf("single-a", "gcp", () => new Response("should not"));

  const response = await send(`https://single-a.${ZONE}/`, {}, multiEnv(null));

  assert.equal(response.body, "pc");
  assert.equal(response.headers.get("x-lily-cloud"), null);
});
