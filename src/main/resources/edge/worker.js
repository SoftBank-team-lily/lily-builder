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
// 쓰기 큐(queue.js)에 등록된 앱의 POST 는 클라우드로 다시 보내지 않고 Durable Object 에 쌓았다가 PC 가 돌아오면 다시 보낸다.

import { queued, WriteQueue } from "./queue.js";

export { WriteQueue };

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

const down = new Map();

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const cloud = cloudUrl(url);
    if (cloud === null) {
      return fetch(request);
    }
    const host = url.hostname;
    const write = await queued(request, url, env, () => isDown(host), () => markDown(host, ctx));
    if (write !== null) {
      return write;
    }
    if (await isDown(host)) {
      return toCloud(request, null, cloud, host);
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
      return toCloud(request, body, cloud, host);
    }
    if (!(await edgeFailed(response))) {
      return response;
    }
    markDown(host, ctx);
    if (response.status !== 530 && !SAFE.has(request.method)) {
      // PC 가 받았을 수 있다. 두 번 처리되지 않게 오류를 그대로 돌려준다 (다음 요청부터는 클라우드로)
      return response;
    }
    return toCloud(request, body, cloud, host);
  },
};

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
    return new Response("cloud origin unavailable", { status: 502 });
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
