#!/usr/bin/env bash

# Собирает краткое состояние и подозрительные записи логов всех Docker-контейнеров.
# Ничего не изменяет: использует только docker ps, docker inspect и docker logs.

set -uo pipefail

since="24h"
tail_lines="5000"
context_lines="3"
exclude_pattern=""
error_pattern='(^|[^[:alpha:]])(error|exception|fatal|panic|critical)([^[:alpha:]]|$)|segmentation fault|out of memory|oomkilled|killed process|connection refused|connection reset|timed? out|timeout|unhealthy|failed to|failure'

usage() {
  cat <<'EOF'
Usage: docker-error-report.sh [options]

Options:
  --since VALUE       Период логов Docker: 30m, 6h, 24h, 7d или timestamp (default: 24h)
  --tail NUMBER       Максимум последних строк на контейнер (default: 5000)
  --context NUMBER    Строк контекста до и после совпадения (default: 3)
  --pattern REGEX     Свой шаблон ошибок для grep -E
  --exclude REGEX     Не проверять контейнеры, имя которых совпадает с REGEX
  -h, --help          Показать справку

Examples:
  ./docker-error-report.sh
  ./docker-error-report.sh --since 2h
  ./docker-error-report.sh --since 7d --tail 20000
  ./docker-error-report.sh --exclude 'buildkit|certbot'
EOF
}

die() {
  printf 'Ошибка: %s\n' "$*" >&2
  exit 2
}

is_non_negative_integer() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

while (($# > 0)); do
  case "$1" in
    --since)
      (($# >= 2)) || die "для --since нужно значение"
      since="$2"
      shift 2
      ;;
    --tail)
      (($# >= 2)) || die "для --tail нужно число"
      is_non_negative_integer "$2" || die "--tail должен быть целым неотрицательным числом"
      tail_lines="$2"
      shift 2
      ;;
    --context)
      (($# >= 2)) || die "для --context нужно число"
      is_non_negative_integer "$2" || die "--context должен быть целым неотрицательным числом"
      context_lines="$2"
      shift 2
      ;;
    --pattern)
      (($# >= 2)) || die "для --pattern нужен REGEX"
      error_pattern="$2"
      shift 2
      ;;
    --exclude)
      (($# >= 2)) || die "для --exclude нужен REGEX"
      exclude_pattern="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "неизвестный аргумент: $1 (используйте --help)"
      ;;
  esac
done

command -v docker >/dev/null 2>&1 || die "docker не найден"
docker info >/dev/null 2>&1 || die "нет доступа к Docker daemon"

tmp_dir="$(mktemp -d)" || die "не удалось создать временный каталог"
trap 'rm -rf -- "$tmp_dir"' EXIT INT TERM

manifest="$tmp_dir/containers"
docker ps -aq >"$manifest.ids" || die "не удалось получить список контейнеров"

if [[ ! -s "$manifest.ids" ]]; then
  printf 'Docker-контейнеры не найдены.\n'
  exit 0
fi

printf 'Docker error report — %s\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')"
printf 'Логи: since=%s, максимум %s строк на контейнер\n\n' "$since" "$tail_lines"
printf '%-30s %-20s %-12s %-8s %-8s %-10s %s\n' \
  'CONTAINER' 'SERVICE' 'STATUS' 'RESTARTS' 'EXIT' 'HEALTH' 'PROBLEMS'
printf '%-30s %-20s %-12s %-8s %-8s %-10s %s\n' \
  '------------------------------' '--------------------' '------------' '--------' '--------' '----------' '--------'

: >"$manifest"
state_problem_count=0
container_count=0

while IFS= read -r container_id; do
  [[ -n "$container_id" ]] || continue

  metadata="$(docker inspect --format \
    '{{.Name}}|{{with index .Config.Labels "com.docker.compose.service"}}{{.}}{{else}}-{{end}}|{{.Config.Image}}|{{.State.Status}}|{{.State.ExitCode}}|{{.RestartCount}}|{{.State.OOMKilled}}|{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}|{{.HostConfig.LogConfig.Type}}' \
    "$container_id" 2>/dev/null)" || continue

  IFS='|' read -r name service image status exit_code restart_count oom_killed health log_driver <<<"$metadata"
  name="${name#/}"

  if [[ -n "$exclude_pattern" ]] && grep -Eq "$exclude_pattern" <<<"$name"; then
    continue
  fi

  ((container_count += 1))
  problems=()

  [[ "$status" == "running" ]] || problems+=("state=$status")
  [[ "$status" == "running" || "$exit_code" == "0" ]] || problems+=("exit=$exit_code")
  [[ "$restart_count" == "0" ]] || problems+=("restarts=$restart_count")
  [[ "$oom_killed" == "false" ]] || problems+=("OOMKilled")
  [[ "$health" != "unhealthy" ]] || problems+=("unhealthy")

  if ((${#problems[@]} == 0)); then
    problem_text="-"
  else
    problem_text="$(IFS=,; printf '%s' "${problems[*]}")"
    ((state_problem_count += 1))
  fi

  printf '%-30.30s %-20.20s %-12.12s %-8s %-8s %-10.10s %s\n' \
    "$name" "$service" "$status" "$restart_count" "$exit_code" "$health" "$problem_text"

  printf '%s|%s|%s|%s|%s\n' \
    "$container_id" "$name" "$service" "$image" "$log_driver" >>"$manifest"
done <"$manifest.ids"

printf '\n=== Ошибки в логах ===\n'

log_problem_count=0
unreadable_log_count=0

while IFS='|' read -r container_id name service image log_driver; do
  [[ -n "$container_id" ]] || continue
  log_file="$tmp_dir/${container_id}.log"

  if ! docker logs --timestamps --since "$since" --tail "$tail_lines" \
    "$container_id" >"$log_file" 2>&1; then
    printf '\n[%s] логи недоступны (logging driver: %s)\n' "$name" "$log_driver"
    sed -n '1,5p' "$log_file"
    ((unreadable_log_count += 1))
    continue
  fi

  match_count="$(grep -Eic "$error_pattern" "$log_file" || true)"
  if ((match_count == 0)); then
    continue
  fi

  ((log_problem_count += 1))
  printf '\n--- %s (service=%s, image=%s, совпадений=%s) ---\n' \
    "$name" "$service" "$image" "$match_count"
  grep -Ein -B "$context_lines" -A "$context_lines" \
    "$error_pattern" "$log_file" || true
done <"$manifest"

if ((log_problem_count == 0)); then
  printf '\nЗа период %s строки по шаблону ошибок не найдены.\n' "$since"
fi

printf '\n=== Итог ===\n'
printf 'Проверено контейнеров: %s\n' "$container_count"
printf 'Проблемное состояние/OOM/рестарты: %s\n' "$state_problem_count"
printf 'Контейнеры с подозрительными логами: %s\n' "$log_problem_count"
printf 'Контейнеры с недоступными логами: %s\n' "$unreadable_log_count"
printf '\nВажно: поиск по тексту эвристический; RestartCount относится только к текущему контейнеру.\n'
