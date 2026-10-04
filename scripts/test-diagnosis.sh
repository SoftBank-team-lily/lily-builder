#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUILDER_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
JEV_DIR="$(cd -- "${BUILDER_DIR}/../lily-jev" && pwd)"
ENV_FILE="${BUILDER_DIR}/.env.local"
MODE="ai"
if [[ "${1:-}" == "--rules" ]]; then
  MODE="rules"
elif [[ $# -gt 0 ]]; then
  echo 'Usage: ./scripts/test-diagnosis.sh [--rules]' >&2
  exit 1
fi

for command in docker python3; do
  command -v "$command" >/dev/null || { echo "$command is required." >&2; exit 1; }
done

# Parse as data rather than sourcing the key file as shell code. Never print values.
python3 - "$ENV_FILE" "$MODE" <<'PY'
import pathlib, sys
path = pathlib.Path(sys.argv[1])
if not path.is_file():
    sys.exit(f"Create {path} from .env.example and fill in the values.")
values = {}
for line in path.read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#') and '=' in line:
        name, value = line.split('=', 1)
        values[name.strip()] = value.strip()
if not values.get('DIAGNOSIS_API_TOKEN'):
    sys.exit('Set DIAGNOSIS_API_TOKEN in .env.local.')
if sys.argv[2] == 'ai' and not values.get('JEV_API_KEY'):
    sys.exit('Paste your TypeSafe key after JEV_API_KEY= in .env.local, then run again.')
PY

docker info >/dev/null
echo 'Building lily-builder with the local lily-jev module…'
docker run --rm \
  -e GRADLE_USER_HOME=/gradle \
  -v lily-builder-gradle:/gradle \
  -v "${BUILDER_DIR}:/workspace/lily-builder" \
  -v "${JEV_DIR}:/workspace/lily-jev" \
  -w /workspace/lily-builder \
  eclipse-temurin:21-jdk ./gradlew bootJar --no-daemon --console=plain

CONTAINER="lily-diagnosis-local-$$"
cleanup() { docker rm -f "$CONTAINER" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM
SERVER_ENV=(--env-file "$ENV_FILE")
if [[ "$MODE" == "rules" ]]; then SERVER_ENV+=(-e JEV_API_KEY=); fi
docker run -d --name "$CONTAINER" \
  -p 127.0.0.1::8070 \
  "${SERVER_ENV[@]}" \
  -e REMEDIATE_ENABLED=false \
  -e KUBERNETES_AUTH_TRYKUBECONFIG=false \
  -e KUBERNETES_AUTH_TRYSERVICEACCOUNT=false \
  -v "${BUILDER_DIR}/build/libs/app.jar:/app.jar:ro" \
  eclipse-temurin:21-jre java -jar /app.jar \
  --lily.builder.registry=test.invalid/lily \
  --lily.builder.resume-deploys=false >/dev/null
ADDRESS="$(docker port "$CONTAINER" 8070/tcp)"

python3 - "http://${ADDRESS}" "$ENV_FILE" "$MODE" <<'PY'
import datetime, json, pathlib, sys, time, urllib.error, urllib.request
base, env_path, mode = sys.argv[1:]
values = {}
for line in pathlib.Path(env_path).read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#') and '=' in line:
        name, value = line.split('=', 1)
        values[name.strip()] = value.strip()
deadline = time.monotonic() + 60
while True:
    try:
        with urllib.request.urlopen(base + '/', timeout=2) as response:
            if response.status == 200:
                break
    except (OSError, urllib.error.URLError):
        pass
    if time.monotonic() >= deadline:
        sys.exit('Local builder did not start within 60 seconds.')
    time.sleep(1)

body = {
    'app': 'diagnosis-test', 'namespace': 'default',
    'observedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'evidence': [
        {'id': 'pod-1', 'source': 'pods', 'signal': 'oom',
         'summary': 'Synthetic fixture: current container terminated with OOMKilled, exitCode=137, because its memory limit was exceeded.'},
        {'id': 'log-1', 'source': 'logs', 'signal': 'oom',
         'summary': 'Synthetic fixture from the same termination: java.lang.OutOfMemoryError: Java heap space.'},
    ], 'missingSources': ['metrics', 'status', 'deployment'],
}
request = urllib.request.Request(base + '/api/diagnoses', data=json.dumps(body).encode(),
    headers={'Content-Type': 'application/json',
             'Authorization': 'Bearer ' + values['DIAGNOSIS_API_TOKEN']})
try:
    with urllib.request.urlopen(request, timeout=10) as response:
        result = json.load(response)
except urllib.error.HTTPError as error:
    sys.exit(f'Diagnosis request failed with HTTP {error.code}.')
except (OSError, ValueError) as error:
    sys.exit('Diagnosis request could not complete. Check the local builder and network connection.')

assert result['app'] == body['app'] and result['namespace'] == body['namespace']
assert result['category'] in {'resources', 'unknown'}
assert set(result['evidenceIds']) <= {'pod-1', 'log-1'}
expected = 'rules' if mode == 'rules' else 'ai'
print(json.dumps({k: result[k] for k in ('source', 'category', 'summary', 'evidenceIds', 'limitations')},
                 ensure_ascii=False, indent=2))
if result['source'] != expected:
    sys.exit('HTTP connection succeeded, but JEV was not applied. Check the key, connectivity and limitations above.')
print('PASS: ' + ('JEV returned a valid decision.' if mode == 'ai' else 'Rules fallback works without an AI key.'))
PY
