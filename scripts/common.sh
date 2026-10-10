#!/usr/bin/env bash
# novel-browse 部署公共库：配置加载 / 构建 / 发布 / 启停 / 健康检查
# 兼容 bash 3.2+（macOS 默认）与 Linux bash 5，仅依赖 bash、java、curl、git（构建时需要）
#
# 日志函数统一输出到 stderr，stdout 只保留数据结果，便于脚本之间组合。

# ---------------------------------------------------------------------------
# 基础路径与源码目录探测
# ---------------------------------------------------------------------------
# 仅支持 bash：dash（Ubuntu 的 /bin/sh）不支持 BASH_SOURCE、pipefail 等特性
if [ -z "${BASH_VERSION:-}" ]; then
    printf '[ERROR] common.sh 需要 bash 运行，请使用 ./deploy.sh、./app.sh 或 bash <脚本>\n' >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PARENT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# 源码目录自动探测，支持两种目录结构：
#   1) scripts 与源码平级（服务器约定）：~/source/scripts + ~/source/novel-browse
#   2) scripts 位于源码仓库内（本仓库默认）：<repo>/scripts
# 也可在 deploy.conf 中直接指定 SOURCE_DIR 覆盖。
default_source_dir() {
    if [ -f "$PARENT_DIR/novel-browse/settings.gradle" ] || [ -x "$PARENT_DIR/novel-browse/gradlew" ]; then
        printf '%s' "$PARENT_DIR/novel-browse"
    elif [ -f "$PARENT_DIR/settings.gradle" ] || [ -x "$PARENT_DIR/gradlew" ]; then
        printf '%s' "$PARENT_DIR"
    else
        printf '%s' "$PARENT_DIR/novel-browse"
    fi
}

# ---------------------------------------------------------------------------
# 默认配置（可被 scripts/deploy.conf、$APP_ROOT/deploy.conf 或环境变量覆盖）
# ---------------------------------------------------------------------------
: "${SOURCE_DIR:=$(default_source_dir)}"             # 源码目录（服务器示例：$HOME/source/novel-browse）
: "${APP_ROOT:=$HOME/apps/novel-browse}"            # 部署根目录
: "${APP_NAME:=novel-browse}"
: "${PORT:=8080}"
: "${SPRING_PROFILES_ACTIVE:=}"
: "${MANAGER:=auto}"                                # auto | nohup | systemd
: "${SERVICE_NAME:=novel-browse}"
: "${RUN_USER:=$(id -un)}"
: "${KEEP_RELEASES:=5}"
: "${BRANCH:=}"                                     # 为空表示当前分支
: "${GIT_REMOTE:=origin}"
: "${JAVA_OPTS:=-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai}"
: "${EXTRA_ARGS:=}"                                 # 追加的 Spring Boot 启动参数
: "${HEALTH_PATH:=/categories}"
: "${HEALTH_TIMEOUT:=90}"                           # 健康检查最长等待秒数
: "${HEALTH_INTERVAL:=2}"
: "${STOP_TIMEOUT:=30}"                             # 优雅停止最长等待秒数
: "${MIN_JAVA_VERSION:=21}"
: "${BUILD_TASK:=clean bootJar}"
: "${GRADLE_EXTRA_ARGS:=--no-daemon -x test}"
: "${JAVA_BIN:=}"
: "${GRADLE_BIN:=}"                                 # 指定 gradle 可执行文件（默认用源码目录的 gradlew）

# ---------------------------------------------------------------------------
# 日志输出
# ---------------------------------------------------------------------------
log()  { printf '%s [INFO ] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; }
warn() { printf '%s [WARN ] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; }
err()  { printf '%s [ERROR] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; }
die()  { err "$*"; exit 1; }

# ---------------------------------------------------------------------------
# 配置加载与派生路径
# ---------------------------------------------------------------------------
load_config_file() {
    [ -f "$1" ] || return 0
    log "加载配置：$1"
    # shellcheck disable=SC1090
    . "$1"
}

init_paths() {
    RELEASES_DIR="$APP_ROOT/releases"
    CURRENT_LINK="$APP_ROOT/current"
    LOG_DIR="$APP_ROOT/logs"
    RUN_DIR="$APP_ROOT/run"
    DATA_DIR="$APP_ROOT/data"
    PID_FILE="$RUN_DIR/$APP_NAME.pid"
    LOG_FILE="$LOG_DIR/$APP_NAME.log"
    CACHE_DIR="$DATA_DIR/rocksdb-cache"
    ENV_FILE="$APP_ROOT/app.env"
}

# 顺序：脚本同目录配置 → 派生路径 → 部署目录配置（后者优先级更高）
load_config_file "$SCRIPT_DIR/deploy.conf"
init_paths
load_config_file "$APP_ROOT/deploy.conf"
init_paths

# ---------------------------------------------------------------------------
# 环境检查与目录准备
# ---------------------------------------------------------------------------
java_major_version() {
    local raw ver
    if [ -n "$JAVA_BIN" ] && [ -x "$JAVA_BIN" ]; then
        raw=$("$JAVA_BIN" -version 2>&1 | head -n 1)
    else
        raw=$(java -version 2>&1 | head -n 1)
    fi
    ver=$(printf '%s' "$raw" | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p')
    [ -n "$ver" ] || ver=0
    printf '%s' "$ver"
}

resolve_java_bin() {
    if [ -n "$JAVA_BIN" ] && [ -x "$JAVA_BIN" ]; then
        printf '%s' "$JAVA_BIN"
        return 0
    fi
    command -v java 2>/dev/null || true
}

check_requirements() {
    local need_build="${1:-false}"
    command -v java >/dev/null 2>&1 || [ -n "$JAVA_BIN" ] || \
        die "未找到 java，请先安装 JDK ${MIN_JAVA_VERSION}（Ubuntu: sudo apt install -y openjdk-${MIN_JAVA_VERSION}-jdk-headless）"
    local ver
    ver=$(java_major_version)
    if [ "$ver" -lt "$MIN_JAVA_VERSION" ] 2>/dev/null; then
        die "JDK 版本过低：当前 ${ver}，需要 ${MIN_JAVA_VERSION}+（Ubuntu: sudo apt install -y openjdk-${MIN_JAVA_VERSION}-jdk-headless）"
    fi
    command -v curl >/dev/null 2>&1 || die "未找到 curl，请先安装（Ubuntu: sudo apt install -y curl）"
    if [ "$need_build" = "true" ]; then
        command -v git >/dev/null 2>&1 || die "未找到 git，无法拉取代码（Ubuntu: sudo apt install -y git）"
        # 注意：gradlew 的校验放在 git pull 之后（check_build_tool），
        # 否则源码仓库尚未更新到含 Wrapper 的版本时会误报缺失。
        [ -f "$SOURCE_DIR/settings.gradle" ] || die "源码目录不正确：${SOURCE_DIR}（缺少 settings.gradle）"
    fi
}

ensure_dirs() {
    mkdir -p "$RELEASES_DIR" "$LOG_DIR" "$RUN_DIR" "$DATA_DIR" || die "创建部署目录失败：$APP_ROOT"
}

# ---------------------------------------------------------------------------
# Git 与构建
# ---------------------------------------------------------------------------
pull_code() {
    if [ ! -d "$SOURCE_DIR/.git" ]; then
        warn "$SOURCE_DIR 不是 git 仓库，跳过代码拉取"
        return 0
    fi
    if [ -n "$BRANCH" ]; then
        log "更新代码：$GIT_REMOTE/$BRANCH"
        git -C "$SOURCE_DIR" fetch "$GIT_REMOTE" "$BRANCH" >&2 || die "git fetch 失败"
        git -C "$SOURCE_DIR" checkout "$BRANCH" >&2 || die "git checkout $BRANCH 失败"
        git -C "$SOURCE_DIR" pull --ff-only "$GIT_REMOTE" "$BRANCH" >&2 || die "git pull 失败"
    else
        log "更新代码：git pull --ff-only"
        git -C "$SOURCE_DIR" pull --ff-only >&2 || die "git pull 失败（可能存在本地改动，请先处理）"
    fi
}

# 校验构建工具可用性：优先 Gradle Wrapper（缺执行权限会自动补），其次系统 gradle。
# 需在 git pull 之后调用，避免源码未更新时误判。
check_build_tool() {
    if [ -n "$GRADLE_BIN" ]; then
        [ -x "$GRADLE_BIN" ] || command -v "$GRADLE_BIN" >/dev/null 2>&1 || \
            die "GRADLE_BIN 不可用：${GRADLE_BIN}"
        return 0
    fi
    if [ -f "$SOURCE_DIR/gradlew" ]; then
        if [ ! -x "$SOURCE_DIR/gradlew" ]; then
            warn "gradlew 缺少执行权限，已自动补上（chmod +x）"
            chmod +x "$SOURCE_DIR/gradlew" 2>/dev/null || true
        fi
        return 0
    fi
    if command -v gradle >/dev/null 2>&1; then
        warn "源码目录缺少 gradlew，改用系统 gradle：$(command -v gradle)"
        return 0
    fi
    die "未找到 Gradle：${SOURCE_DIR}/gradlew 不存在（请确认仓库已包含 Gradle Wrapper，可 git pull 后重试），也未检测到系统 gradle；可安装 gradle 或配置 GRADLE_BIN"
}

# 执行 Gradle：Wrapper 不可执行时用 sh 调用，仍不可用则退回系统 gradle
run_gradle() {
    if [ -n "$GRADLE_BIN" ]; then
        "$GRADLE_BIN" "$@"
    elif [ -x "$SOURCE_DIR/gradlew" ]; then
        "$SOURCE_DIR/gradlew" "$@"
    elif [ -f "$SOURCE_DIR/gradlew" ]; then
        sh "$SOURCE_DIR/gradlew" "$@"
    elif command -v gradle >/dev/null 2>&1; then
        gradle "$@"
    else
        return 127
    fi
}

build_app() {
    log "开始构建：gradle ${BUILD_TASK} ${GRADLE_EXTRA_ARGS}"
    ( cd "$SOURCE_DIR" && run_gradle ${BUILD_TASK} ${GRADLE_EXTRA_ARGS} ) >&2 || die "Gradle 构建失败"
    log "构建完成"
}

pick_jar() {
    local jar
    jar=$(ls -1t "$SOURCE_DIR"/build/libs/*.jar 2>/dev/null | grep -v -- '-plain\.jar$' | head -n 1)
    [ -n "$jar" ] && [ -f "$jar" ] || die "未找到构建产物 $SOURCE_DIR/build/libs/*.jar"
    printf '%s' "$jar"
}

resolve_version() {
    local commit ts
    commit=$(git -C "$SOURCE_DIR" rev-parse --short HEAD 2>/dev/null || true)
    [ -n "$commit" ] || commit="manual"
    ts=$(date '+%Y%m%d-%H%M%S')
    printf '%s-%s' "$commit" "$ts"
}

# ---------------------------------------------------------------------------
# 版本发布与切换
# ---------------------------------------------------------------------------
create_release() {
    local jar="$1" version dir
    version=$(resolve_version)
    dir="$RELEASES_DIR/$version"
    mkdir -p "$dir" || die "创建版本目录失败：$dir"
    cp -f "$jar" "$dir/app.jar" || die "复制构建产物失败"
    {
        printf 'RELEASE="%s"\n' "$version"
        printf 'BUILT_AT="%s"\n' "$(date '+%Y-%m-%d %H:%M:%S')"
        printf 'JAR="%s"\n' "$(basename "$jar")"
        printf 'SIZE="%s"\n' "$(wc -c < "$dir/app.jar" | tr -d ' ')"
        printf 'COMMIT="%s"\n' "$(git -C "$SOURCE_DIR" rev-parse HEAD 2>/dev/null || echo unknown)"
    } > "$dir/release.env"
    printf '%s' "$dir"
}

current_release() {
    [ -L "$CURRENT_LINK" ] || return 1
    local target
    target=$(readlink "$CURRENT_LINK" 2>/dev/null || true)
    [ -n "$target" ] || return 1
    # 保持与 $RELEASES_DIR/*/ 相同的路径形式，便于比较（不做符号链接解析）
    case "$target" in
        /*) printf '%s' "$target" ;;
        *)  printf '%s/%s' "$(cd "$(dirname "$CURRENT_LINK")" && pwd)" "$target" ;;
    esac
}

activate_release() {
    local target="$1"
    [ -f "$target/app.jar" ] || die "版本目录无效（缺少 app.jar）：$target"
    ln -sfn "$target" "$CURRENT_LINK" || die "切换 current 软链接失败"
    log "当前版本已切换：$(basename "$target")"
}

previous_release() {
    local current dir found
    current=$(current_release 2>/dev/null || true)
    found=0
    ls -1dt "$RELEASES_DIR"/*/ 2>/dev/null | while IFS= read -r dir; do
        dir="${dir%/}"
        if [ "$dir" = "$current" ]; then
            found=1
            continue
        fi
        if [ "$found" = "1" ]; then
            printf '%s' "$dir"
            break
        fi
    done
}

cleanup_releases() {
    local keep="$KEEP_RELEASES"
    [ "$keep" -gt 0 ] 2>/dev/null || return 0
    local current index dir
    current=$(current_release 2>/dev/null || true)
    index=0
    ls -1dt "$RELEASES_DIR"/*/ 2>/dev/null | while IFS= read -r dir; do
        dir="${dir%/}"
        index=$((index + 1))
        [ "$index" -le "$keep" ] && continue
        [ "$dir" = "$current" ] && continue
        log "清理旧版本：$(basename "$dir")"
        rm -rf "$dir"
    done
}

# ---------------------------------------------------------------------------
# 运行管理（nohup / systemd）
# ---------------------------------------------------------------------------
systemd_available() {
    command -v systemctl >/dev/null 2>&1 || return 1
    [ -d /run/systemd/system ] || return 1
    return 0
}

sudo_cmd() {
    if [ "$(id -u)" -eq 0 ]; then
        printf ''
    else
        printf 'sudo'
    fi
}

manager_mode() {
    case "$MANAGER" in
        nohup|systemd) printf '%s' "$MANAGER" ;;
        *)
            if systemd_available && [ -f "/etc/systemd/system/$SERVICE_NAME.service" ]; then
                printf 'systemd'
            else
                printf 'nohup'
            fi
            ;;
    esac
}

is_running() {
    [ -f "$PID_FILE" ] || return 1
    local pid
    pid=$(cat "$PID_FILE" 2>/dev/null)
    [ -n "$pid" ] || return 1
    kill -0 "$pid" 2>/dev/null
}

# 端口是否被监听（兼容 ss / lsof / netstat）
port_in_use() {
    if command -v ss >/dev/null 2>&1; then
        ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$PORT$"
    elif command -v lsof >/dev/null 2>&1; then
        lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1
    elif command -v netstat >/dev/null 2>&1; then
        netstat -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]$PORT$"
    else
        return 1
    fi
}

# 端口占用进程提示信息（拿不到 pid 时返回空）
port_listener_hint() {
    if command -v lsof >/dev/null 2>&1; then
        lsof -nP -iTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | awk 'NR>1 {printf "%s(pid %s) ", $1, $2}'
    elif command -v ss >/dev/null 2>&1; then
        ss -ltnp 2>/dev/null | awk -v p=":$PORT" '$4 ~ p' \
            | grep -oE '"[^"]+",pid=[0-9]+' \
            | sed 's/"//g; s/,pid=/(pid /; s/$/)/' | tr '\n' ' '
    fi
}

# 渲染模板：把 @@VAR@@ 占位符替换为当前配置值
render_template() {
    local tpl="$1" out="$2" group
    [ -f "$tpl" ] || die "模板文件不存在：$tpl"
    group=$(id -gn "$RUN_USER" 2>/dev/null || echo "$RUN_USER")
    sed -e "s|@@APP_NAME@@|$APP_NAME|g" \
        -e "s|@@SOURCE_DIR@@|$SOURCE_DIR|g" \
        -e "s|@@APP_ROOT@@|$APP_ROOT|g" \
        -e "s|@@RUN_USER@@|$RUN_USER|g" \
        -e "s|@@RUN_GROUP@@|$group|g" \
        -e "s|@@JAVA_BIN@@|$(resolve_java_bin)|g" \
        -e "s|@@CURRENT_LINK@@|$CURRENT_LINK|g" \
        -e "s|@@CACHE_DIR@@|$CACHE_DIR|g" \
        -e "s|@@LOG_DIR@@|$LOG_DIR|g" \
        -e "s|@@LOG_FILE@@|$LOG_FILE|g" \
        -e "s|@@PORT@@|$PORT|g" \
        -e "s|@@EXTRA_ARGS@@|$EXTRA_ARGS|g" \
        "$tpl" > "$out" || die "渲染模板失败：$tpl"
}

app_args() {
    local args
    args="--server.port=$PORT --rocksdb.cache.path=$CACHE_DIR"
    [ -n "$SPRING_PROFILES_ACTIVE" ] && args="$args --spring.profiles.active=$SPRING_PROFILES_ACTIVE"
    [ -n "$EXTRA_ARGS" ] && args="$args $EXTRA_ARGS"
    printf '%s' "$args"
}

start_app_nohup() {
    ensure_dirs
    [ -f "$CURRENT_LINK/app.jar" ] || die "未激活任何版本（$CURRENT_LINK/app.jar 不存在）"
    if is_running; then
        die "服务已在运行（pid $(cat "$PID_FILE")）"
    fi
    if port_in_use; then
        die "端口 $PORT 已被占用：$(port_listener_hint)请先处理该进程（./scripts/app.sh status 可查看状态）"
    fi
    local java_bin
    java_bin=$(resolve_java_bin)
    [ -n "$java_bin" ] || die "未找到 java 可执行文件"
    # shellcheck disable=SC2086
    log "启动服务：java ${JAVA_OPTS} -DLOG_DIR=$LOG_DIR -jar app.jar $(app_args)"
    (
        cd "$CURRENT_LINK" || exit 1
        # shellcheck disable=SC2086
        nohup "$java_bin" ${JAVA_OPTS} -DLOG_DIR="$LOG_DIR" -jar app.jar $(app_args) >> "$LOG_FILE" 2>&1 &
        printf '%s' "$!" > "$PID_FILE"
    )
    sleep 1
    if ! is_running; then
        err "进程未能启动，最近日志："
        tail -n 30 "$LOG_FILE" >&2 2>/dev/null || true
        return 1
    fi
    log "进程已启动：pid $(cat "$PID_FILE")"
    return 0
}

stop_app_nohup() {
    if ! is_running; then
        rm -f "$PID_FILE"
        if port_in_use; then
            warn "未找到有效 pid 文件，但端口 $PORT 仍被占用：$(port_listener_hint)请手动确认该进程是否需要清理"
        else
            warn "服务未在运行"
        fi
        return 0
    fi
    local pid waited
    pid=$(cat "$PID_FILE")
    log "停止服务：pid $pid"
    kill "$pid" 2>/dev/null || true
    waited=0
    while kill -0 "$pid" 2>/dev/null; do
        if [ "$waited" -ge "$STOP_TIMEOUT" ]; then
            warn "优雅停止超时（${STOP_TIMEOUT}s），强制结束"
            kill -9 "$pid" 2>/dev/null || true
            break
        fi
        sleep 1
        waited=$((waited + 1))
    done
    rm -f "$PID_FILE"
    log "服务已停止"
}

start_app() {
    case "$(manager_mode)" in
        systemd)
            local sc
            sc=$(sudo_cmd)
            log "systemd 启动：$SERVICE_NAME"
            # shellcheck disable=SC2086
            $sc systemctl start "$SERVICE_NAME" || return 1
            ;;
        *)
            start_app_nohup || return 1
            ;;
    esac
    health_check
}

stop_app() {
    case "$(manager_mode)" in
        systemd)
            local sc
            sc=$(sudo_cmd)
            log "systemd 停止：$SERVICE_NAME"
            # shellcheck disable=SC2086
            $sc systemctl stop "$SERVICE_NAME" || return 1
            ;;
        *)
            stop_app_nohup || return 1
            ;;
    esac
}

restart_app() {
    case "$(manager_mode)" in
        systemd)
            local sc
            sc=$(sudo_cmd)
            log "systemd 重启：$SERVICE_NAME"
            # shellcheck disable=SC2086
            $sc systemctl restart "$SERVICE_NAME" || return 1
            ;;
        *)
            stop_app_nohup || return 1
            start_app_nohup || return 1
            ;;
    esac
    health_check
}

health_check() {
    local url waited
    url="http://127.0.0.1:$PORT$HEALTH_PATH"
    log "健康检查：${url}（最长等待 ${HEALTH_TIMEOUT}s）"
    waited=0
    while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
        if curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
            log "健康检查通过（耗时 ${waited}s）"
            return 0
        fi
        sleep "$HEALTH_INTERVAL"
        waited=$((waited + HEALTH_INTERVAL))
    done
    err "健康检查失败：$url"
    return 1
}

status_app() {
    local mode release state pid
    mode=$(manager_mode)
    release=$(current_release 2>/dev/null || true)
    printf '应用名称   : %s\n' "$APP_NAME"
    printf '运行模式   : %s\n' "$mode"
    printf '源码目录   : %s\n' "$SOURCE_DIR"
    printf '部署目录   : %s\n' "$APP_ROOT"
    printf '当前版本   : %s\n' "${release:-（未激活）}"
    if [ "$mode" = "systemd" ]; then
        state=$(systemctl is-active "$SERVICE_NAME" 2>/dev/null || true)
        pid=$(systemctl show -p MainPID --value "$SERVICE_NAME" 2>/dev/null || true)
        printf '服务状态   : %s\n' "${state:-unknown}"
        printf '主进程 PID : %s\n' "${pid:-0}"
    elif is_running; then
        printf '服务状态   : running\n'
        printf '主进程 PID : %s\n' "$(cat "$PID_FILE")"
    else
        printf '服务状态   : stopped\n'
        printf '主进程 PID : -\n'
    fi
    printf '监听端口   : %s\n' "$PORT"
    if port_in_use; then
        printf '端口占用   : 是 %s\n' "$(port_listener_hint)"
    else
        printf '端口占用   : 否\n'
    fi
    printf '日志文件   : %s\n' "$LOG_FILE"
    printf '缓存目录   : %s\n' "$CACHE_DIR"
    if curl -fsS --max-time 3 "http://127.0.0.1:$PORT$HEALTH_PATH" >/dev/null 2>&1; then
        printf '健康检查   : OK (%s%s)\n' "http://127.0.0.1:$PORT" "$HEALTH_PATH"
    else
        printf '健康检查   : FAILED (%s%s)\n' "http://127.0.0.1:$PORT" "$HEALTH_PATH"
    fi
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || die "未找到命令：$1"
}
