// lily-edge: 온프레미스 앱 공개 주소 {app}.{zone} 앞에서 돈다 (EdgeWorker 가 앱마다 라우트를 건다).
//
// 요청은 원래 오리진(CNAME 내용물: 평소 PC 터널, 거점이 클라우드면 ALB)으로 그대로 보낸다.
// PC 터널이 받지 못하면(530 = 터널 커넥터 없음, 또는 연결 실패) 같은 요청을 {app}-cloud.{zone}(ALB → 클라우드 대기 Pod)으로 다시 보낸다.
// 530 은 PC 가 요청을 받지 못한 경우라 POST 도 다시 보내도 된다. 앱이 돌려준 5xx 는 PC 장애와 구분할 수 없어 다시 보내지 않는다.

/** PC 가 받지 못한 뒤 이 시간 동안은 PC 를 건너뛰고 바로 클라우드로 보낸다 (isolate 메모리, 엣지 위치마다 따로) */
const DOWN_MILLIS = 10_000;
/** 다시 보내려고 메모리에 들고 있을 본문 크기 상한. 넘거나 길이를 모르면 다시 보내지 않는다 */
const REPLAY_LIMIT = 1024 * 1024;
const CLOUD_SUFFIX = "-cloud";

const down = new Map();

export default {
  async fetch(request) {
    const url = new URL(request.url);
    const cloud = cloudUrl(url);
    if (cloud === null) {
      return fetch(request);
    }
    const host = url.hostname;
    if ((down.get(host) ?? 0) > Date.now()) {
      return toCloud(request, null, cloud, host);
    }

    const body = await replayableBody(request);
    if (body === undefined) {
      // 다시 보낼 수 없는 요청 (큰 본문, 길이 모르는 스트림). 원래 오리진으로만 보낸다
      return fetch(request);
    }
    try {
      const response = await fetch(rebuild(request, url, body));
      if (response.status !== 530) {
        return response;
      }
    } catch (e) {
      // 오리진에 연결하지 못했다
    }
    down.set(host, Date.now() + DOWN_MILLIS);
    return toCloud(request, body, cloud, host);
  },
};

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
