# 部署脚本

面向 Ubuntu / 麒麟等 Linux 服务器的部署与运维脚本。服务器约定：源码 `~/source/novel-browse`，脚本 `~/source/scripts`，部署产物与运行数据放 `~/apps/novel-browse`。

## 目录结构

脚本支持两种放置方式，源码目录会自动探测（无需手工指定 `SOURCE_DIR`）：

**方式一（服务器推荐）：scripts 与源码平级**

```text
~/source/                            # 部署机工作目录
├── novel-browse/                    # 源码仓库（git clone 位置）
└── scripts/                         # 本目录（部署脚本，与源码分离便于重装/重拉）

~/apps/novel-browse/                 # 部署根目录（APP_ROOT）
├── releases/
│   ├── 185e282-20261010-234500/     # 版本目录：commit + 构建时间
│   │   ├── app.jar
│   │   └── release.env              # 版本元数据（构建时间、commit、大小）
│   └── ...
├── current -> releases/<最新版本>/   # 软链接，服务始终运行该版本
├── logs/novel-browse.log            # 运行日志
├── run/novel-browse.pid             # pid 文件（nohup 模式）
├── data/rocksdb-cache/              # RocksDB 缓存目录
└── app.env                          # systemd 模式下的运行参数
```

**方式二：scripts 位于源码仓库内（`<repo>/scripts`）**，即 `~/source/novel-browse/scripts`，无需任何配置也能直接执行。

> 探测顺序：`$SCRIPT_DIR/../novel-browse` 存在 `settings.gradle` / `gradlew` 就用它，否则若 `$SCRIPT_DIR/..` 是仓库根目录就用它；两者都不是时按方式一推断。也可在 `deploy.conf` 中显式写 `SOURCE_DIR=` 覆盖。

版本目录 + `current` 软链接的方式保证：每次部署都是**新目录发布 → 切换链接 → 重启**，历史版本可随时回滚。

## 脚本清单

| 脚本 | 用途 |
| --- | --- |
| `deploy.sh` | 一键部署：拉码 → 构建 → 发布新版本 → 切换 → 重启 → 健康检查（失败自动回滚）→ 清理旧版本 |
| `app.sh` | 日常运维：`start` / `stop` / `restart` / `status` / `health` / `log` / `releases` |
| `install-service.sh` | 安装为 systemd 服务（含日志轮转），需要 sudo |
| `common.sh` | 公共配置与函数（被上述脚本引用） |
| `deploy.conf.example` | 配置样例，复制为 `deploy.conf` 后按需修改 |
| `novel-browse.service.tpl` | systemd 单元模板 |
| `logrotate.novel-browse.tpl` | 日志轮转模板 |

## 首次部署

下面按服务器约定的结构操作（脚本在 `~/source/scripts`，源码在 `~/source/novel-browse`）：

```bash
# 1. 准备环境（JDK 21、curl、git）
sudo apt update
sudo apt install -y openjdk-21-jdk-headless git curl

# 2. 拉取源码仓库
mkdir -p ~/source && cd ~/source
git clone <仓库地址> novel-browse

# 3. 放置部署脚本（与源码平级；也可直接用仓库内的 novel-browse/scripts）
cp -r ~/source/novel-browse/scripts ~/source/scripts
cd ~/source/scripts && chmod +x *.sh   # 必须可执行，且用 bash 运行（./deploy.sh，不要用 sh deploy.sh）

# 4.（可选）个性化配置
cp deploy.conf.example deploy.conf
vim deploy.conf                    # 端口、JVM 参数、分支等

# 5. 部署
./deploy.sh

# 6.（可选）交给 systemd 托管
./install-service.sh               # 之后 deploy.sh 自动使用 systemctl
```

首次部署会自动完成 Gradle Wrapper 下载（腾讯云镜像）、构建、启动，并等待 `/categories` 健康检查通过。

> 脚本更新：脚本已随仓库提交，`deploy.sh` 只会更新 `SOURCE_DIR` 下的代码。若把脚本放在了 `~/source/scripts`（仓库外），需要手动同步一次：`cp -r ~/source/novel-browse/scripts/. ~/source/scripts/`（`deploy.conf` 不会被覆盖，因为仓库里只有 `deploy.conf.example`）。

## 日常操作

在脚本目录下执行（`cd ~/source/scripts`；若脚本在仓库内则是 `<repo>/scripts`）：

```bash
./deploy.sh                      # 拉取最新代码并部署
./deploy.sh --skip-pull          # 用当前工作区代码部署（不 git pull）
./deploy.sh --skip-build         # 复用 build/libs 产物，仅重新发布
./deploy.sh --rollback           # 回滚到上一个版本
./deploy.sh -b release-1.0 -k 7  # 部署指定分支，保留最近 7 个版本

./app.sh status                  # 版本、进程、端口、健康检查
./app.sh restart                 # 重启
./app.sh log                     # 实时日志（尾 100 行）
./app.sh releases                # 历史版本列表
```

## 配置项（脚本目录下的 `deploy.conf`）

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SOURCE_DIR` | 自动探测（见上） | 源码目录，服务器上即 `~/source/novel-browse` |
| `APP_ROOT` | `~/apps/novel-browse` | 部署根目录 |
| `PORT` | `8080` | 服务端口 |
| `SPRING_PROFILES_ACTIVE` | 空 | 激活的 Spring profile |
| `MANAGER` | `auto` | `auto`：存在 systemd 单元则用 systemd，否则用 nohup |
| `JAVA_OPTS` | `-Xms256m -Xmx512m -XX:+UseG1GC …` | JVM 参数，按服务器内存调整 |
| `EXTRA_ARGS` | 空 | 追加的 Spring Boot 参数，如 `--rocksdb.cache.ttl=30m` |
| `JAVA_BIN` | 自动探测 | 指定 JDK 绝对路径 |
| `BRANCH` | 空（当前分支） | 部署分支 |
| `KEEP_RELEASES` | `5` | 保留的历史版本数 |
| `BUILD_TASK` | `clean bootJar` | Gradle 构建任务 |
| `GRADLE_EXTRA_ARGS` | `--no-daemon -x test` | 构建附加参数 |
| `GRADLE_BIN` | 空（用源码目录的 `gradlew`） | 指定 Gradle 可执行文件；gradlew 缺失时自动回退到系统 `gradle` |
| `HEALTH_PATH` | `/categories` | 健康检查路径 |
| `HEALTH_TIMEOUT` | `90` | 健康检查最长等待秒数 |
| `STOP_TIMEOUT` | `30` | 优雅停止最长等待秒数 |

配置加载优先级：环境变量 > `$APP_ROOT/deploy.conf` > `<脚本目录>/deploy.conf` > 脚本默认值。

## 部署流程细节

```text
deploy.sh
  ├─ 检查基础环境（JDK ≥ 21、curl、git、settings.gradle）
  ├─ git pull --ff-only            （--skip-pull 可跳过）
  ├─ 校验构建工具（gradlew，缺执行权限自动补；缺失则退回系统 gradle）
  ├─ ./gradlew clean bootJar -x test（--skip-build 可跳过）
  ├─ 复制 jar → releases/<commit>-<时间>/app.jar，写 release.env
  ├─ 停止旧版本（SIGTERM，最多等 STOP_TIMEOUT 秒，超时再 SIGKILL）
  ├─ ln -sfn 切换 current 软链接
  ├─ 启动新版本 → 轮询 /categories 直至通过
  │    └─ 失败：自动切回上一个版本并重启，脚本以非 0 退出
  └─ 清理旧版本（保留 KEEP_RELEASES 个，始终保留 current）
```

> `gradlew` 的校验特意放在 `git pull` **之后**：若源码仓库尚未更新到含 Gradle Wrapper 的提交，先校验会误报"未找到 Gradle Wrapper"，导致拉码也被跳过。

几点说明：

- **构建与运存分离**：Gradle 构建需要额外内存，服务器内存紧张时可将 `GRADLE_EXTRA_ARGS` 中的 `-x test` 保留，并适当调小 `JAVA_OPTS`。
- **RocksDB 目录**：启动参数显式传入 `--rocksdb.cache.path=$APP_ROOT/data/rocksdb-cache`，与源码目录解耦。因内存库每次重启都会重新生成数据，默认 `clean-on-start=true`，重启后缓存自动清空；如需保留可设置 `EXTRA_ARGS="--rocksdb.cache.clean-on-start=false"`。
- **端口占用**：启动前会检测端口，被非本脚本管理的进程占用时直接报错退出（不触发回滚，避免误伤在跑的服务），可用 `sudo ss -ltnp | grep :8080` 排查。
- **systemd 模式**：`ExecStart` 中的 `${JAVA_OPTS}` 来自 `app.env`，修改 `JAVA_OPTS` 后需重新执行 `install-service.sh`（或直接编辑 `app.env` 后 `sudo systemctl restart novel-browse`）。
- **日志**：应用使用 `logback-spring.xml`，默认输出到控制台；nohup 模式由脚本追加写入 `logs/novel-browse.log`，安装 systemd 时同步安装 logrotate（每日轮转、保留 14 份、copytruncate）。脚本启动时自动传 `-DLOG_DIR=$LOG_DIR`（即 `$APP_ROOT/logs`）。
- **调整日志级别**：在 `deploy.conf` 里 `EXTRA_ARGS="--logging.level.com.novel.browse=DEBUG"`，或设置环境变量 `LOG_LEVEL_APP=DEBUG`（前者优先）。
- **让日志框架自己写文件**：`SPRING_PROFILES_ACTIVE="file"`，日志会额外写入 `$LOG_DIR/novel-browse.log` 并按天 + 50MB 滚动（保留 30 天、总上限 1GB）；此时注意 logrotate 与框架滚动的取舍，通常二选一。

## 常见问题

| 现象 | 处理 |
| --- | --- |
| `sh: 14: set: Illegal option -o pipefail` | 用 `sh deploy.sh` 运行了脚本（Ubuntu 的 `/bin/sh` 是 dash，不支持 `pipefail`）。请用 `./deploy.sh` 或 `bash deploy.sh`；当前版本的脚本检测到 `sh` 会自动切回 bash 重新执行 |
| `未找到 Gradle Wrapper：.../gradlew` | 源码目录没有 `gradlew`。先确认 `git -C ~/source/novel-browse log --oneline -1` 是否已更新到含 Wrapper 的提交（脚本现在会先 `git pull` 再校验，正常可直接重跑）；若仓库确实没有 Wrapper，可安装 `gradle` 或配置 `GRADLE_BIN` |
| `Permission denied` | 未赋执行权限：`chmod +x *.sh` |
| `JDK 版本过低` | 安装 JDK 21：`sudo apt install -y openjdk-21-jdk-headless`，或配置 `JAVA_BIN` |
| 构建失败提示无法下载 Gradle / 依赖 | 检查服务器网络；`settings.gradle` 中已配置腾讯云镜像 + 官方源兜底 |
| `健康检查失败` 后自动回滚 | 查看 `~/apps/novel-browse/logs/novel-browse.log`；必要时 `./app.sh log` |
| 端口被占用 | `sudo ss -ltnp | grep :8080`；修改 `deploy.conf` 的 `PORT` 后重新部署 |
| 想彻底停止并下线 | `./app.sh stop`；systemd 模式再加 `./install-service.sh --uninstall` |
