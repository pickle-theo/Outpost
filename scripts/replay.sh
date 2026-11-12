#!/usr/bin/env bash
# Outpost 紧急回灌包装脚本（Git Bash / Linux / macOS）
# 用法示例：
#   SINCE='2026-11-01 00:00:00' UNTIL='2026-11-02 00:00:00' \
#   NAMESPACE=order BIZ_TYPE=cache_delete MYSQL_PWD='***' ./scripts/replay.sh
set -euo pipefail

SINCE="${SINCE:?必须设置 SINCE（业务变更起始时间）}"
UNTIL="${UNTIL:?必须设置 UNTIL（业务变更结束时间，不含）}"
NAMESPACE="${NAMESPACE:-order}"
BIZ_TYPE="${BIZ_TYPE:-cache_delete}"
MAX_RETRY="${MAX_RETRY:-10}"
LOG_TABLE="${LOG_TABLE:-biz_cache_change_log}"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_DB="${MYSQL_DB:-outpost}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP_SQL="$(mktemp)"
trap 'rm -f "$TMP_SQL"' EXIT

sed -e "s|:SINCE|'${SINCE}'|g" \
    -e "s|:UNTIL|'${UNTIL}'|g" \
    -e "s|:NAMESPACE|'${NAMESPACE}'|g" \
    -e "s|:BIZ_TYPE|'${BIZ_TYPE}'|g" \
    -e "s|:MAX_RETRY|${MAX_RETRY}|g" \
    -e "s|:TABLE|${LOG_TABLE}|g" \
    "${SCRIPT_DIR}/backfill.sql" > "${TMP_SQL}"

echo "[replay] ${SINCE} ~ ${UNTIL}, namespace=${NAMESPACE}, bizType=${BIZ_TYPE}"
mysql -h "${MYSQL_HOST}" -P "${MYSQL_PORT}" -u "${MYSQL_USER}" "${MYSQL_DB}" < "${TMP_SQL}"
echo "[replay] 完成，请执行 shadow-reconcile.sh 复核漏删"
