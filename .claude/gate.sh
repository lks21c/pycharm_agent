#!/usr/bin/env bash
# repo-local: github.com/lks21c/pycharm_agent
# repo-local-run: as portal build
# pycharm_agent 게이트 어댑터 — 저장소 슬롯 (옛 명령 /as · /runIde · /pab 를 옮김, lks21c/arcane#26).
#
#   setup <wt>          gradle wrapper 와 JDK 가 있는지만 본다 — 설치하지 않는다
#   verify [--merge]    plugin/ 에서 ./gradlew test
#   teardown <wt>       워크트리를 지운다(미커밋이 있으면 --force 없이는 거절)
#   sweep               할 것 없음 — 이 저장소는 데몬 · 컨테이너를 남기지 않는다
#   as [동작]           agent-server(FastAPI, uvicorn) — start|stop|restart|status|logs. 옛 /as.
#                       서버 코드는 이 저장소 밖이라 AGENT_SERVER_DIR(agent-server 체크아웃)로 받는다
#   portal [동작]       플러그인을 실은 시험 IDE(./gradlew runIde) — start|stop|restart|status. 옛 /runIde
#   build [plugin]      ./gradlew buildPlugin → plugin/build/distributions/*.zip. 옛 /pab
#
# JDK 는 JAVA_HOME 으로 준다(기계마다 경로가 다르다 — 이 파일에 적지 않는다). rc: 0 통과 · 1 red · 2 사용법 · 3 판정 불가.
set -u
cmd="${1:-}"; shift 2>/dev/null || true
ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
STATE="${XDG_STATE_HOME:-$HOME/.local/state}/pycharm_agent"
AS_PORT="${AGENT_SERVER_PORT:-8000}"

need_java() {
    [ -x "$ROOT/plugin/gradlew" ] || { echo "!! gate: plugin/gradlew 가 없다 (판정 불가)" >&2; exit 3; }
    if [ -n "${JAVA_HOME:-}" ]; then [ -x "$JAVA_HOME/bin/java" ] || { echo "!! gate: JAVA_HOME/bin/java 가 없다: $JAVA_HOME (판정 불가)" >&2; exit 3; }
    else command -v java >/dev/null 2>&1 || { echo "!! gate: JDK 가 없다 — JAVA_HOME 을 준다 (판정 불가)" >&2; exit 3; }; fi
}

bg() {  # bg <이름> <명령…> — 로그는 $STATE/<이름>.log, pid 는 $STATE/<이름>.pid
    name="$1"; shift; mkdir -p "$STATE"
    nohup "$@" > "$STATE/$name.log" 2>&1 & echo $! > "$STATE/$name.pid"
    echo "$name 시작: pid $(cat "$STATE/$name.pid") · 로그 $STATE/$name.log"
}
alive() { [ -f "$STATE/$1.pid" ] && kill -0 "$(cat "$STATE/$1.pid")" 2>/dev/null; }
stop() {
    if alive "$1"; then pkill -TERM -P "$(cat "$STATE/$1.pid")" 2>/dev/null; kill "$(cat "$STATE/$1.pid")"; echo "$1 멈춤"; else echo "$1 안 떠 있음"; fi
    rm -f "$STATE/$1.pid"
}
daemon() {  # daemon <이름> <동작> <start 함수>
    name="$1"; act="$2"; starter="$3"
    case "$act" in
        start) if alive "$name"; then echo "$name 이미 떠 있음: pid $(cat "$STATE/$name.pid")"; else "$starter"; fi ;;
        stop) stop "$name" ;;
        restart) stop "$name"; sleep 2; "$starter" ;;
        status) if alive "$name"; then echo "$name 떠 있음: pid $(cat "$STATE/$name.pid")"; else echo "$name 안 떠 있음"; fi ;;
        logs) tail -n "${4:-50}" "$STATE/$name.log" 2>/dev/null || { echo "!! $name 로그 없음" >&2; exit 3; } ;;
        *) echo "사용법: gate.sh $cmd [start|stop|restart|status|logs]" >&2; exit 2 ;;
    esac
}

start_as() {
    [ -n "${AGENT_SERVER_DIR:-}" ] && [ -d "$AGENT_SERVER_DIR" ] \
        || { echo "!! gate: AGENT_SERVER_DIR 가 없다 — agent-server 체크아웃 경로를 준다 (판정 불가)" >&2; exit 3; }
    ( cd "$AGENT_SERVER_DIR" && bg as poetry run uvicorn agent_server.main:app --host 0.0.0.0 --port "$AS_PORT" )
    sleep 3; curl -s "http://localhost:$AS_PORT/health" || echo "기동 중 — gate.sh as status 로 다시 본다"
}
start_ide() { need_java; ( cd "$ROOT/plugin" && bg ide ./gradlew runIde ); }

case "$cmd" in
  setup) need_java; echo "setup: 설치할 것 없음 — gradle wrapper · JDK 확인" ;;
  verify) need_java; ( cd "$ROOT/plugin" && ./gradlew test ) || exit 1 ;;
  teardown)
    wt="${1:?사용법: gate.sh teardown <wt> [--force]}"
    if [ "${2:-}" = --force ]; then git worktree remove --force "$wt"; else git worktree remove "$wt"; fi || exit 1 ;;
  sweep) echo "sweep: 할 것 없음 — 데몬 · 컨테이너를 남기지 않는다" ;;
  as) daemon as "${1:-status}" start_as "${2:-}" ;;
  portal)
    act="${1:-status}"; [ "$act" = logs ] && { echo "사용법: gate.sh portal [start|stop|restart|status]" >&2; exit 2; }
    daemon ide "$act" start_ide ;;
  build)
    [ "${1:-plugin}" = plugin ] || { echo "사용법: gate.sh build [plugin]" >&2; exit 2; }
    need_java; ( cd "$ROOT/plugin" && ./gradlew buildPlugin ) || exit 1
    ls -la "$ROOT"/plugin/build/distributions/*.zip || { echo "!! gate: 산출 zip 이 없다" >&2; exit 1; } ;;
  *) echo "사용법: gate.sh setup <wt> | verify [--merge] | teardown <wt> [--force] | sweep | as|portal [동작] | build [plugin]" >&2; exit 2 ;;
esac
