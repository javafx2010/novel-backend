#!/usr/bin/env bash
# novel-browse 安装为 systemd 服务（需要 sudo）
#   写入 /etc/systemd/system/<SERVICE_NAME>.service 与 /etc/logrotate.d/<APP_NAME>
#   安装后 scripts/deploy.sh / app.sh 会自动以 systemd 模式运行（MANAGER=auto）
#
# 用法: ./scripts/install-service.sh [选项]
#   -u, --user NAME   运行服务的系统用户（默认当前用户）
#   --no-logrotate    不安装日志轮转配置
#   --uninstall       卸载 systemd 单元与日志轮转配置（保留程序与数据）
#   -h, --help        显示帮助
#
# 注意：必须用 bash 运行（直接 ./install-service.sh 或 bash install-service.sh）。
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

UNIT_PATH=""            # 由 init_unit_path 赋值
LOGROTATE_PATH=""
INSTALL_LOGROTATE=true
UNINSTALL=false

usage() {
    sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        -u|--user)     shift; RUN_USER="${1:-}" ;;
        --no-logrotate) INSTALL_LOGROTATE=false ;;
        --uninstall)   UNINSTALL=true ;;
        -h|--help)     usage ;;
        *) die "未知参数：$1（使用 --help 查看用法）" ;;
    esac
    shift
done

init_unit_path() {
    UNIT_PATH="/etc/systemd/system/$SERVICE_NAME.service"
    LOGROTATE_PATH="/etc/logrotate.d/$APP_NAME"
}

sudo_run() {
    local sc
    sc=$(sudo_cmd)
    # shellcheck disable=SC2086
    if [ -n "$sc" ]; then
        $sc "$@"
    else
        "$@"
    fi
}

write_env_file() {
    ensure_dirs
    cat > "$ENV_FILE" <<EOF
# 由 scripts/install-service.sh 生成，供 systemd EnvironmentFile 读取
# 修改后执行: sudo systemctl restart $SERVICE_NAME
JAVA_OPTS="$JAVA_OPTS"
SPRING_PROFILES_ACTIVE="$SPRING_PROFILES_ACTIVE"
EOF
    if [ "$(id -u)" -eq 0 ] && [ "$RUN_USER" != "root" ]; then
        chown "$RUN_USER" "$ENV_FILE" 2>/dev/null || true
    fi
    log "已写入运行参数：$ENV_FILE"
}

uninstall_service() {
    log "卸载 systemd 单元：$UNIT_PATH"
    sudo_run systemctl stop "$SERVICE_NAME" 2>/dev/null || true
    sudo_run systemctl disable "$SERVICE_NAME" 2>/dev/null || true
    sudo_run rm -f "$UNIT_PATH"
    sudo_run rm -f "$LOGROTATE_PATH"
    sudo_run systemctl daemon-reload || true
    log "卸载完成（程序文件与数据保留在 ${APP_ROOT}）"
}

install_service() {
    systemd_available || die "当前系统没有可用的 systemd，无法安装服务"
    check_requirements false
    ensure_dirs
    init_unit_path

    require_cmd sed

    local tmp_unit tmp_logrotate
    tmp_unit=$(mktemp)
    tmp_logrotate=$(mktemp)
    trap 'rm -f "$tmp_unit" "$tmp_logrotate"' EXIT

    render_template "$SCRIPT_DIR/novel-browse.service.tpl" "$tmp_unit" || die "生成 systemd 单元失败"
    write_env_file

    log "安装 systemd 单元：${UNIT_PATH}（运行用户：${RUN_USER}）"
    sudo_run cp "$tmp_unit" "$UNIT_PATH" || die "写入 $UNIT_PATH 失败"
    sudo_run chmod 0644 "$UNIT_PATH"

    if [ "$INSTALL_LOGROTATE" = "true" ]; then
        render_template "$SCRIPT_DIR/logrotate.novel-browse.tpl" "$tmp_logrotate" || die "生成日志轮转配置失败"
        log "安装日志轮转配置：$LOGROTATE_PATH"
        sudo_run cp "$tmp_logrotate" "$LOGROTATE_PATH" || warn "写入 $LOGROTATE_PATH 失败，跳过"
        sudo_run chmod 0644 "$LOGROTATE_PATH" || true
    fi

    sudo_run systemctl daemon-reload || die "systemctl daemon-reload 失败"
    sudo_run systemctl enable "$SERVICE_NAME" || warn "systemctl enable 失败，请手动执行"

    log "安装完成。后续使用："
    printf '  启动/停止/状态 : ./scripts/app.sh start|stop|status\n' >&2
    printf '  一键部署      : ./scripts/deploy.sh\n' >&2
    printf '  systemd 日志  : sudo journalctl -u %s -n 100 -f\n' "$SERVICE_NAME" >&2
}

init_unit_path
if [ "$UNINSTALL" = "true" ]; then
    uninstall_service
else
    install_service
fi
