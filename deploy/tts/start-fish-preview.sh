#!/usr/bin/env bash
set -euo pipefail

FISH_PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

fish_java_is_21() {
  local fish_java_version
  [[ -x "$1" ]] || return 1
  fish_java_version="$("$1" -version 2>&1)" || return 1
  case "$fish_java_version" in
    *'version "21.'*|*'version "21"'*|*'version "21-'*) return 0 ;;
    *) return 1 ;;
  esac
}

if [[ -z "${CLOSURE_FISH_API_KEY:-}" && ! -f "$FISH_PROJECT_ROOT/config/local-fish-voice.properties" ]]; then
  printf '%s\n' '尚未配置 Fish API key。请先按 deploy/tts/FISH.md 在本机配置；无需把密钥发到聊天中。' >&2
  exit 1
fi

if [[ -n "${JAVA_HOME:-}" ]] && fish_java_is_21 "$JAVA_HOME/bin/java"; then
  : # Keep a Java 21 installation already selected by the caller.
elif [[ -x /usr/libexec/java_home ]]; then
  FISH_JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)" || {
    printf '%s\n' '需要 Java 21；请安装或通过 JAVA_HOME 指向已有的 Java 21。' >&2
    exit 1
  }
  if ! fish_java_is_21 "$FISH_JAVA_HOME/bin/java"; then
    printf '%s\n' '未找到可用的 Java 21。' >&2
    exit 1
  fi
  export JAVA_HOME="$FISH_JAVA_HOME"
else
  FISH_JAVA_BIN="$(command -v java || true)"
  if [[ -n "${JAVA_HOME:-}" ]] || ! fish_java_is_21 "$FISH_JAVA_BIN"; then
    printf '%s\n' '需要 Java 21；请通过 JAVA_HOME 或 PATH 选择已有的 Java 21。' >&2
    exit 1
  fi
fi

export CLOSURE_VOICE_BACKEND=fish
export CLOSURE_FISH_MODEL="${CLOSURE_FISH_MODEL:-s2.1-pro-free}"
case "$CLOSURE_FISH_MODEL" in
  s2.1-pro-free|drama-3) ;;
  *) printf '%s\n' 'CLOSURE_FISH_MODEL 只接受 s2.1-pro-free 或 drama-3。' >&2; exit 1 ;;
esac
export CLOSURE_GPT_SOVITS_SERVER_AUTO_START=false
cd -- "$FISH_PROJECT_ROOT"
printf 'Fish 语音预览（%s）：主动发声会调用云端；s2.1-pro-free 当前免费，drama-3 按 API 计费。\n' "$CLOSURE_FISH_MODEL"
exec ./mvnw javafx:run
