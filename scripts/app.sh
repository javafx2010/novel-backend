#!/usr/bin/env bash
# novel-browse 日常运维
#
# 用法: ./scripts/app.sh <命令>
#   start     启动服务并做健康检查
#   stop      停止服务
#   restart   重启服务并做健康检查
#   status    查看运行状态、当前版本与健康检查结果
#   health    仅执行健康检查
#   log       实时查看日志（tail -f，Ctrl+C 退出）
#   releases  列出已发布的版本（含创建时间与大小）
#   help      显示帮助
#
# 注意：必须用 bash 运行（直接 ./app.sh 或 bash app.sh）。误用 sh 时脚本会自动切回 bash。
#
# 兼容误用 sh 调用：自动改用 bash 重新执行
if [ -z "${BASH_VERSION:-}" ]; then
    if [ -n "${NOVEL_BROWSE_REEXEC:-}" ]; then
        printf '[ERROR] 自动切换 bash 失败，请手动执行：bash %s\n' "$0" >&2
        exit 1
    fi
    if command -v bash >/dev/null 2>&1; then
        printf '[WARN] 检测到使用 sh 运行，已自动切换为 bash 执行\n' >&2
        NOVEL_BROWSE_REEXEC=1 exec bash "$0" "$@"
    fi
    printf '[ERROR] 本脚本需要 bash 运行（Ubuntu 的 sh 为 dash，不支持所需语法）\n' >&2
    exit 1
fi

set -u -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
. "$SCRIPT_DIR/common.sh"

usage() {
    sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

list_releases() {
    local dir current
    current=$(current_release 2>/dev/null || true)
    if [ ! -d "$RELEASES_DIR" ]; then
        printf '暂无发布记录（%s 不存在）\n' "$RELEASES_DIR"
        return 0
    fi
    printf '%-28s %-20s %10s  %s\n' "版本" "构建时间" "大小" "状态"
    ls -1dt "$RELEASES_DIR"/*/ 2>/dev/null | while IFS= read -r dir; do
        dir="${dir%/}"
        [ -f "$dir/release.env" ] || continue
        # shellcheck disable=SC1090
        ( . "$dir/release.env"
          size=$(wc -c < "$dir/app.jar" | tr -d ' ')
          mark=""
          [ "$dir" = "$current" ] && mark="← 当前"
          printf '%-28s %-20s %10s  %s\n' "$RELEASE" "$BUILT_AT" "$(human_size "$size")" "$mark"
        )
    done
}

human_size() {
    local bytes="$1"
    if [ "$bytes" -ge 1048576 ] 2>/dev/null; then
        printf '%sMB' "$((bytes / 1048576))"
    elif [ "$bytes" -ge 1024 ] 2>/dev/null; then
        printf '%sKB' "$((bytes / 1024))"
    else
        printf '%sB' "$bytes"
    fi
}

COMMAND="${1:-help}"
case "$COMMAND" in
    start)
        check_requirements false
        ensure_dirs
        start_app || die "启动失败，请检查 $LOG_FILE"
        ;;
    stop)
        check_requirements false
        stop_app || die "停止失败"
        ;;
    restart)
        check_requirements false
        ensure_dirs
        restart_app || die "重启失败，请检查 $LOG_FILE"
        ;;
    status)
        status_app
        ;;
    health)
        check_requirements false
        if health_check; then
            printf '健康检查通过\n'
        else
            printf '健康检查失败\n'
            exit 1
        fi
        ;;
    log)
        [ -f "$LOG_FILE" ] || die "日志文件不存在：$LOG_FILE"
        printf '正在查看 %s（Ctrl+C 退出）\n' "$LOG_FILE"
        tail -n 100 -f "$LOG_FILE"
        ;;
    releases)
        list_releases
        ;;
    help|--help|-h)
        usage
        ;;
    *)
        die "未知命令：${COMMAND}（使用 help 查看用法）"
        ;;
esac
