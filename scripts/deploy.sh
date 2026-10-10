#!/usr/bin/env bash
# novel-browse 一键部署
#   拉取代码 → Gradle 构建 → 发布新版本目录 → 切换 current → 重启服务 → 健康检查 → 清理旧版本
#   健康检查失败时自动回滚到上一个版本
#
# 用法: ./scripts/deploy.sh [选项]
#   --skip-pull        跳过 git 拉取，直接用当前工作区代码构建
#   --skip-build       跳过构建，使用 build/libs 下已有产物
#   --no-cleanup       保留全部历史版本（不清理）
#   --rollback         回滚到上一个版本（不构建、不拉取）
#   -k, --keep N       保留最近 N 个版本（默认取配置 KEEP_RELEASES）
#   -b, --branch NAME  指定部署分支
#   -h, --help         显示帮助
#
# 注意：必须用 bash 运行（直接 ./deploy.sh 或 bash deploy.sh）。
#      Ubuntu 的 /bin/sh 是 dash，不支持 pipefail 等特性，误用 sh 时脚本会自动切回 bash。
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

SKIP_PULL=false
SKIP_BUILD=false
NO_CLEANUP=false
DO_ROLLBACK=false

usage() {
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --skip-pull)  SKIP_PULL=true ;;
        --skip-build) SKIP_BUILD=true ;;
        --no-cleanup) NO_CLEANUP=true ;;
        --rollback)   DO_ROLLBACK=true ;;
        -k|--keep)    shift; KEEP_RELEASES="${1:-}" ;;
        -b|--branch)  shift; BRANCH="${1:-}" ;;
        -h|--help)    usage ;;
        *) die "未知参数：$1（使用 --help 查看用法）" ;;
    esac
    shift
done

# ---------------------------------------------------------------- 回滚分支
do_rollback() {
    local target
    target=$(previous_release)
    [ -n "$target" ] || die "没有可回滚的历史版本（${RELEASES_DIR}）"
    log "回滚目标：$(basename "$target")"
    stop_app || true
    activate_release "$target"
    start_app || die "回滚后启动失败，请检查 $LOG_FILE"
    log "回滚完成：$(basename "$target")"
}

if [ "$DO_ROLLBACK" = "true" ]; then
    check_requirements false
    ensure_dirs
    do_rollback
    exit 0
fi

# ---------------------------------------------------------------- 正常部署
log "开始部署 $APP_NAME"
log "源码目录：$SOURCE_DIR"
log "部署目录：$APP_ROOT"

check_requirements true
ensure_dirs

if [ "$SKIP_PULL" = "true" ]; then
    warn "跳过代码拉取（--skip-pull）"
else
    pull_code
fi

if [ "$SKIP_BUILD" = "true" ]; then
    warn "跳过构建（--skip-build），使用现有产物"
else
    build_app
fi

JAR=$(pick_jar)
log "构建产物：${JAR}（$(wc -c < "$JAR" | tr -d ' ') 字节）"

PREVIOUS=$(current_release 2>/dev/null || true)
NEW_RELEASE=$(create_release "$JAR")
log "新版本目录：$NEW_RELEASE"

stop_app || warn "停止旧版本时出现问题，继续部署"

activate_release "$NEW_RELEASE"

if ! start_app; then
    err "新版本启动失败：$(basename "$NEW_RELEASE")"
    if [ -n "$PREVIOUS" ]; then
        warn "尝试回滚到上一个版本：$(basename "$PREVIOUS")"
        stop_app || true
        activate_release "$PREVIOUS"
        if start_app; then
            warn "已回滚到 $(basename "$PREVIOUS")，新版本仍保留在 $NEW_RELEASE"
        else
            err "回滚后仍启动失败，请检查 $LOG_FILE"
            exit 1
        fi
    else
        err "没有可回滚的版本，请检查 $LOG_FILE"
    fi
    exit 1
fi

if [ "$NO_CLEANUP" = "true" ]; then
    log "跳过历史版本清理（--no-cleanup）"
else
    cleanup_releases
fi

log "部署完成：$(basename "$NEW_RELEASE")"
status_app
