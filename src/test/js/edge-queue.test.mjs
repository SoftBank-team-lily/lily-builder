// edge/queue.js 테스트. Node 24 이상 (node:sqlite): gradle edgeTest 또는 node --test src/test/js/edge-queue.test.mjs
// Durable Object 의 SQLite 저장소와 alarm 은 node:sqlite 와 메모리 값으로 흉내 낸다. 바깥 fetch 는 테스트가 정한다.
import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { createHash } from "node:crypto";
import { queued, WriteQueue, STATUS_PATH } from "../../main/resources/edge/queue.js";

const HOST = "blog.lilycloud.kr";
const ADMIN = "lily-edge-queue.lilycloud.kr";
const KEY = Buffer.alloc(32, 7).toString("base64");
/** builder 의 EdgeQueue.adminToken() 과 같은 값 */
const ADMIN_TOKEN = createHash("sha256").update("lily-edge-admin:" + KEY).digest("hex");

let pc;
let sent;
let queues;
let env;

beforeEach(async () => {
  sent = [];
  pc = async () => new Response("created", { status: 201 });
  globalThis.fetch = async (input, init) => {
    const request = new Request(input, init);
    const body = await request.text();
    sent.push({ method: request.method, url: request.url, headers: request.headers, body });
    return pc(request, body);
  };
  queues = new Map();
  env = {
    QUEUE_KEY: KEY,
    QUEUE_ADMIN_HOST: ADMIN,
    QUEUE: {
      idFromName: (name) => name,
      get: (id) => {
        if (!queues.has(id)) {
          queues.set(id, newQueue());
        }
        const queue = queues.get(id);
        return { fetch: (input, init) => queue.fetch(new Request(input, init)) };
      },
    },
  };
  assert.equal((await configure(["/posts"])).status, 200);
});

/** builder 가 관리 주소로 등록 경로를 바꾼다 */
async function configure(paths, host = HOST) {
  const { response } = await submit(new Request("https://" + ADMIN + "/apps/" + host + "/queue/config", {
    method: "PUT",
    headers: { authorization: "Bearer " + ADMIN_TOKEN, "content-type": "application/json" },
    body: JSON.stringify({ paths }),
  }));
  return response;
}

async function adminState(host = HOST) {
  const { response } = await submit(new Request("https://" + ADMIN + "/apps/" + host + "/queue",
    { headers: { authorization: "Bearer " + ADMIN_TOKEN } }));
  return response.json();
}

function newQueue() {
  const db = new DatabaseSync(":memory:");
  let alarm = null;
  const sql = {
    exec(query, ...params) {
      const bound = params.map((p) => (p instanceof ArrayBuffer ? new Uint8Array(p) : p));
      if (!/^\s*SELECT/i.test(query)) {
        if (bound.length === 0) {
          db.exec(query);
        } else {
          db.prepare(query).run(...bound);
        }
        return { toArray: () => [], one: () => undefined };
      }
      const rows = db.prepare(query).all(...bound).map((row) => Object.fromEntries(Object.entries(row)
        .map(([k, v]) => [k, v instanceof Uint8Array ? v.buffer.slice(v.byteOffset, v.byteOffset + v.byteLength) : v])));
      return { toArray: () => rows, one: () => rows[0] };
    },
  };
  const queue = new WriteQueue({
    storage: {
      sql,
      getAlarm: async () => alarm,
      setAlarm: async (at) => { alarm = at; },
      deleteAlarm: async () => { alarm = null; },
    },
  }, env);
  queue.db = db;
  queue.alarmAt = () => alarm;
  return queue;
}

function post(path, body, headers = {}) {
  return new Request("https://" + HOST + path, {
    method: "POST",
    headers: { "content-type": "application/json", "content-length": String(Buffer.byteLength(body)), ...headers },
    body,
  });
}

async function submit(request, { down = false } = {}) {
  let marked = false;
  const response = await queued(request, new URL(request.url), env, async () => down, () => { marked = true; });
  return { response, marked };
}

function blogQueue() {
  return queues.get(HOST);
}

test("등록_경로의_POST가_PC에_연결되지_못하면_큐에_넣고_202와_큐_id를_준다", async () => {
  pc = async () => { throw new TypeError("connect failed"); };

  const { response, marked } = await submit(post("/posts", "{\"title\":\"a\"}"));

  assert.equal(response.status, 202);
  const id = response.headers.get("X-Lily-Queued-Id");
  assert.match(id, /^[0-9a-f-]{36}$/);
  assert.equal(response.headers.get("Location"), STATUS_PATH + id);
  assert.deepEqual(await response.json(), { queuedId: id, position: 1 });
  assert.equal(marked, true);
  assert.notEqual(blogQueue().alarmAt(), null);
});

test("등록_경로의_POST가_530을_받으면_큐에_넣고_202를_준다", async () => {
  pc = async () => new Response("tunnel", { status: 530 });

  const { response } = await submit(post("/posts", "{}"));

  assert.equal(response.status, 202);
});

test("큐가_비어_있고_PC가_정상이면_POST를_PC로_보내고_PC_응답_201을_준다", async () => {
  const { response, marked } = await submit(post("/posts", "{\"title\":\"a\"}", { cookie: "SESSION=s1" }));

  assert.equal(response.status, 201);
  assert.equal(await response.text(), "created");
  assert.equal(marked, false);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].url, "https://" + HOST + "/posts");
  assert.equal(sent[0].body, "{\"title\":\"a\"}");
  assert.equal(sent[0].headers.get("cookie"), "SESSION=s1");
  assert.ok(sent[0].headers.get("X-Lily-Edge-Queue"), "Worker 가 큐를 건너뛸 표시를 붙인다");
  assert.equal(sent[0].headers.get("X-Lily-Replay"), null);
});

test("PC_장애_표시가_있으면_PC에_보내지_않고_큐에_넣는다", async () => {
  const { response } = await submit(post("/posts", "{}"), { down: true });

  assert.equal(response.status, 202);
  assert.equal(sent.length, 0);
});

test("큐에_요청이_남아_있으면_PC가_정상이어도_새_POST는_PC에_보내지_않고_큐_뒤에_붙는다", async () => {
  await submit(post("/posts", "{\"n\":1}"), { down: true });

  const { response } = await submit(post("/posts", "{\"n\":2}"));

  assert.equal(response.status, 202);
  assert.equal((await response.json()).position, 2);
  assert.equal(sent.length, 0);
});

test("PC에_보낸_뒤_엣지_502_오류_페이지가_오면_큐에_넣지_않고_502를_주고_PC_장애를_표시한다", async () => {
  pc = async () => new Response("<html><a href=\"/cdn-cgi/l/\">bad gateway</a></html>",
    { status: 502, headers: { "content-type": "text/html" } });

  const { response, marked } = await submit(post("/posts", "{}"));

  assert.equal(response.status, 502);
  assert.equal(response.headers.get("X-Lily-Pc-Down"), null);
  assert.equal(marked, true);
  assert.equal(blogQueue().pending(), 0);
});

test("앱이_돌려준_500은_큐에_넣지_않고_그대로_준다", async () => {
  pc = async () => new Response("boom", { status: 500, headers: { "content-type": "text/plain" } });

  const { response, marked } = await submit(post("/posts", "{}"));

  assert.equal(response.status, 500);
  assert.equal(marked, false);
  assert.equal(blogQueue().pending(), 0);
});

test("동시에_들어온_POST_두_개는_받은_순서대로_순번이_붙는다", async () => {
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  pc = async () => { await gate; throw new TypeError("connect failed"); };

  const first = submit(post("/posts", "{\"n\":1}"));
  const second = submit(post("/posts", "{\"n\":2}"));
  release();
  const [a, b] = await Promise.all([first, second]);

  assert.equal((await a.response.json()).position, 1);
  assert.equal((await b.response.json()).position, 2);
});

test("PC가_돌아오면_alarm이_쌓인_POST를_받은_순서대로_다시_보내고_sent로_바꾼다", async () => {
  const ids = [];
  for (const n of [1, 2]) {
    const { response } = await submit(post("/posts", "{\"n\":" + n + "}"), { down: true });
    ids.push(response.headers.get("X-Lily-Queued-Id"));
  }

  await blogQueue().alarm();

  const replays = sent.filter((r) => r.method === "POST");
  assert.deepEqual(replays.map((r) => r.body), ["{\"n\":1}", "{\"n\":2}"]);
  assert.equal(replays[0].headers.get("X-Lily-Replay"), "1");
  assert.equal(replays[0].headers.get("idempotency-key"), ids[0]);
  assert.match(replays[0].headers.get("x-lily-received-at"), /^\d{4}-\d{2}-\d{2}T/);
  assert.equal(sent[0].method, "GET", "보내기 전에 PC 에 닿는지 / 로 본다");
  assert.equal(sent[0].url, "https://" + HOST + "/");
  const status = await (await blogQueue().fetch(new Request("https://queue/status/" + ids[1]))).json();
  assert.equal(status.state, "sent");
  assert.equal(status.status, 201);
  assert.equal(blogQueue().pending(), 0);
});

test("요청이_보낸_Idempotency-Key는_다시_보낼_때_그대로_쓴다", async () => {
  await submit(post("/posts", "{}", { "idempotency-key": "client-key" }), { down: true });

  await blogQueue().alarm();

  assert.equal(sent.find((r) => r.method === "POST").headers.get("idempotency-key"), "client-key");
});

test("PC에_아직_닿지_않으면_alarm은_POST를_보내지_않고_다음_alarm을_예약한다", async () => {
  await submit(post("/posts", "{}"), { down: true });
  pc = async () => new Response("tunnel", { status: 530 });
  const before = Date.now();

  await blogQueue().alarm();

  assert.equal(sent.filter((r) => r.method === "POST").length, 0);
  assert.ok(blogQueue().alarmAt() >= before + 10_000);
  assert.equal(blogQueue().pending(), 1);
});

test("다시_보낸_POST에_503이_오면_그_뒤_요청은_보내지_않고_queued로_남긴다", async () => {
  for (const n of [1, 2]) {
    await submit(post("/posts", "{\"n\":" + n + "}"), { down: true });
  }
  pc = async (request) => request.method === "GET"
    ? new Response("ok")
    : new Response("db down", { status: 503, headers: { "content-type": "text/plain" } });

  await blogQueue().alarm();

  assert.equal(sent.filter((r) => r.method === "POST").length, 1);
  assert.equal(blogQueue().pending(), 2);
});

test("다시_보낸_POST에_409가_오면_failed로_남기고_다음_요청을_보낸다", async () => {
  const ids = [];
  for (const n of [1, 2]) {
    const { response } = await submit(post("/posts", "{\"n\":" + n + "}"), { down: true });
    ids.push(response.headers.get("X-Lily-Queued-Id"));
  }
  pc = async (request, body) => request.method === "POST" && body === "{\"n\":1}"
    ? new Response("conflict", { status: 409 })
    : new Response("ok", { status: 201 });

  await blogQueue().alarm();

  const first = await (await blogQueue().fetch(new Request("https://queue/status/" + ids[0]))).json();
  const second = await (await blogQueue().fetch(new Request("https://queue/status/" + ids[1]))).json();
  assert.equal(first.state, "failed");
  assert.equal(first.status, 409);
  assert.equal(second.state, "sent");
});

test("큐에_저장한_쿠키와_본문은_평문으로_남지_않는다", async () => {
  await submit(post("/posts", "{\"secret\":\"body-text\"}", { cookie: "SESSION=cookie-text" }), { down: true });

  const rows = blogQueue().db.prepare("SELECT * FROM q").all();
  const dump = Buffer.from(JSON.stringify(rows.map((row) => Object.values(row).map((v) =>
    v instanceof Uint8Array ? Buffer.from(v).toString("latin1") : v)))).toString();
  assert.equal(dump.includes("cookie-text"), false);
  assert.equal(dump.includes("body-text"), false);
});

test("결과_조회_GET은_큐_id의_상태와_순번을_준다", async () => {
  const { response } = await submit(post("/posts", "{}"), { down: true });
  const id = response.headers.get("X-Lily-Queued-Id");

  const status = await submit(new Request("https://" + HOST + STATUS_PATH + id));

  assert.equal(status.response.status, 200);
  const json = await status.response.json();
  assert.equal(json.state, "queued");
  assert.equal(json.position, 1);
});

test("없는_큐_id를_조회하면_404", async () => {
  const { response } = await submit(new Request("https://" + HOST + STATUS_PATH + "nope"));

  assert.equal(response.status, 404);
});

test("큐가_1000건이면_새_POST는_503", async () => {
  await submit(post("/posts", "{}"), { down: true });
  blogQueue().db.exec("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 999) "
    + "INSERT INTO q (id, received_at, origin, path, iv, payload, state, updated_at) "
    + "SELECT 'x' || i, 0, 'https://" + HOST + "', '/posts', x'00', x'00', 'queued', 0 FROM n");

  const { response } = await submit(post("/posts", "{}"), { down: true });

  assert.equal(response.status, 503);
});

test("등록_경로가_없는_앱의_POST는_큐가_맡지_않고_null", async () => {
  const request = new Request("https://shop.lilycloud.kr/orders", { method: "POST", body: "{}" });

  const { response } = await submit(request);

  assert.equal(response, null);
});

test("등록_경로의_GET은_큐가_맡지_않고_null", async () => {
  const { response } = await submit(new Request("https://" + HOST + "/posts"));

  assert.equal(response, null);
});

test("길이를_모르는_본문의_POST는_큐가_맡지_않고_null", async () => {
  const request = new Request("https://" + HOST + "/posts", { method: "POST", body: "{}" });
  request.headers.delete("content-length");

  const { response } = await submit(request);

  assert.equal(response, null);
});

test("QUEUE_KEY가_없으면_큐가_맡지_않고_null", async () => {
  delete env.QUEUE_KEY;

  const { response } = await submit(post("/posts", "{}"));

  assert.equal(response, null);
});

test("큐_표시가_맞는_요청은_큐를_건너뛰고_표시를_지워_오리진으로_보낸다", async () => {
  await submit(post("/posts", "{}"));
  const mark = sent[0].headers.get("X-Lily-Edge-Queue");
  sent = [];

  const { response } = await submit(post("/posts", "{\"n\":1}", { "X-Lily-Edge-Queue": mark }), { down: true });

  assert.equal(response.status, 201);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].headers.get("X-Lily-Edge-Queue"), null);
});

test("큐_표시가_틀린_요청은_보통_요청처럼_큐가_맡는다", async () => {
  const { response } = await submit(post("/posts", "{}", { "X-Lily-Edge-Queue": "forged" }), { down: true });

  assert.equal(response.status, 202);
});

test("DO에_닿지_못하면_큐_없이_PC로_보낸다", async () => {
  env.QUEUE.get = () => ({ fetch: async () => { throw new Error("do unavailable"); } });

  const { response } = await submit(post("/posts", "{\"n\":1}"));

  assert.equal(response.status, 201);
  assert.equal(sent[0].body, "{\"n\":1}");
});

test("등록_경로_아래_경로의_POST도_큐가_맡고_이름만_비슷한_경로는_맡지_않는다", async () => {
  const below = await submit(post("/posts/3/comments", "{}"), { down: true });
  const similar = await submit(post("/postsx", "{}"), { down: true });

  assert.equal(below.response.status, 202);
  assert.equal(similar.response, null);
});

test("등록_경로_밖의_POST는_큐가_맡지_않고_null", async () => {
  const { response } = await submit(post("/login", "{}"), { down: true });

  assert.equal(response, null);
});

test("등록_경로를_비우면_새_POST는_큐가_맡지_않고_null", async () => {
  await configure([]);

  const { response } = await submit(post("/posts", "{}"), { down: true });

  assert.equal(response, null);
});

test("관리_주소를_토큰_없이_부르면_401", async () => {
  const { response } = await submit(new Request("https://" + ADMIN + "/apps/" + HOST + "/queue"));

  assert.equal(response.status, 401);
});

test("관리_주소_PUT에_슬래시로_시작하지_않는_경로가_있으면_400이고_등록_경로는_그대로다", async () => {
  const response = await configure(["posts"]);

  assert.equal(response.status, 400);
  assert.deepEqual((await adminState()).paths, ["/posts"]);
});

test("관리_주소_GET은_등록_경로와_상태별_건수와_최근_요청_경로를_주고_본문은_주지_않는다", async () => {
  await submit(post("/posts", "{\"secret\":\"body-text\"}"), { down: true });

  const state = await adminState();

  assert.deepEqual(state.paths, ["/posts"]);
  assert.deepEqual(state.counts, { queued: 1, sent: 0, failed: 0 });
  assert.equal(state.items.length, 1);
  assert.equal(state.items[0].path, "/posts");
  assert.equal(state.items[0].state, "queued");
  assert.equal(JSON.stringify(state).includes("body-text"), false);
});

test("관리_주소_DELETE는_쌓인_요청과_등록_경로와_alarm을_지운다", async () => {
  await submit(post("/posts", "{}"), { down: true });

  const { response } = await submit(new Request("https://" + ADMIN + "/apps/" + HOST + "/queue",
    { method: "DELETE", headers: { authorization: "Bearer " + ADMIN_TOKEN } }));

  assert.equal(response.status, 200);
  const state = await adminState();
  assert.deepEqual(state.paths, []);
  assert.deepEqual(state.counts, { queued: 0, sent: 0, failed: 0 });
  assert.equal(blogQueue().alarmAt(), null);
});
