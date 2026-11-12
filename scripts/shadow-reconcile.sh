#!/usr/bin/env bash
# Outpost 影子对账包装脚本（Git Bash / Linux / macOS）
# 用法示例：
#   SINCE='2026-11-01 00:00:00' UNTIL='2026-11-02 00:00:00' \
#   NAMESPACE=order MYSQL_PWD='***' ./scripts/shadow-reconcile.sh
set -euo pipefail

SINCE="${SINCE:?必须设置 SINCE}"
UNTIL="${UNTIL:?必须设置 UNTIL}"
NAMESPACE="${NAMESPACE:-order}"
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
    -e "s|:TABLE|${LOG_TABLE}|g" \
    "${SCRIPT_DIR}/shadow-reconcile.sql" > "${TMP_SQL}"

echo "[reconcile] ${SINCE} ~ ${UNTIL}, namespace=${NAMESPACE}"
mysql -h "${MYSQL_HOST}" -P "${MYSQL_PORT}" -u "${MYSQL_USER}" --table "${MYSQL_DB}" < "${TMP_SQL}"
