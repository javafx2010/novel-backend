# novel-browse

Java / Spring Boot 编写的在线小说阅读网站后端。**前后端分离**：本工程只提供 REST 接口，不含任何前端页面代码，前端工程（Vue / Vite 等）独立开发部署，通过 CORS 调用本服务。

## 技术栈

| 领域 | 选型 |
| --- | --- |
| 构建工具 | Gradle 9.6.0（Wrapper，发行包走腾讯云镜像） |
| 框架 | Spring Boot 4.1.1（Web / Data JPA / Cache） |
| 数据库 | H2 内存数据库（启动时建表，不含任何种子数据，进程退出即释放） |
| 缓存 | RocksDB（`rocksdbjni`）本地嵌入式实现，自定义 `CacheManager` |
| EPUB 解析 | jsoup + `java.util.zip` + JAXP 自研解析（zip → container.xml → OPF spine） |
| 运行时 | JDK 21 |

## 设计文档

完整设计文档见 [`docs/`](docs/README.md)：架构设计、领域模型与数据设计、接口设计、RocksDB 缓存设计、构建部署与配置。

## 目录结构

```
src/main/java/com/novel/browse
├── NovelBrowseApplication.java     启动类
├── cache/                          RocksDB 缓存实现（CacheManager / Cache / 配置 / 序列化）
├── config/WebConfig.java           跨域配置
├── data/EpubAutoLoader.java        启动扫描上传目录并导入 EPUB（分类按目录自动创建）
├── domain/                         Category / Novel / Chapter 实体
├── repository/                     Spring Data JPA 仓储
├── service/                        业务服务（带 @Cacheable / @CacheEvict）
└── web/
    ├── HomeController.java         首页聚合、分类、排行、搜索
    ├── BookController.java         书籍详情、目录、章节阅读
    ├── CacheAdminController.java   缓存统计 / 清理
    ├── ApiExceptionHandler.java    统一 JSON 异常处理
    └── dto/                        HomeVo / NovelSummary / NovelDetail / ChapterItem / ChapterDetail / PageResult / ApiError
```

## 快速开始

```bash
./gradlew clean build          # 编译打包，产物 build/libs/novel-browse.jar
java -jar build/libs/novel-browse.jar
# 或
./gradlew bootRun
```

服务默认监听 `http://localhost:8080`，接口无统一前缀，按资源命名（`/home`、`/categories`、`/novels` 等）。H2 控制台：`http://localhost:8080/h2-console`（JDBC URL `jdbc:h2:mem:noveldb`，账号 `sa`）。

## 日志

配置文件：`src/main/resources/logback-spring.xml`（Logback，随 `spring-boot-starter` 引入，无需额外依赖）。

- **输出**：默认只输出控制台；服务器部署时脚本把 stdout 重定向到 `~/apps/novel-browse/logs/novel-browse.log`，由 logrotate 每日轮转、保留 14 份
- **格式**：`时间 级别 [线程] logger : 消息`，UTF-8，不输出颜色转义（便于落文件与检索）
- **级别**：业务包 `com.novel.browse` 默认 INFO，可用环境变量 `LOG_LEVEL_APP=DEBUG` 或启动参数 `--logging.level.com.novel.browse=DEBUG` 覆盖（后者优先）
- **日志框架自写文件**（可选）：`SPRING_PROFILES_ACTIVE=file` 时额外追加滚动文件 appender（按天 + 50MB 切分，保留 30 天、总上限 1GB），目录取 `-DLOG_DIR`，部署脚本已自动传入 `$APP_ROOT/logs`
- **DEBUG 内容**：RocksDB 缓存的命中 / 未命中 / 过期，便于排查缓存效果

## 服务器部署

面向 Ubuntu 服务器的部署脚本位于 [`scripts/`](scripts/README.md)，服务器目录结构约定：

```text
~/source/
├── novel-browse/       # 源码仓库
└── scripts/            # 部署脚本（也支持放在仓库内 novel-browse/scripts）
~/apps/novel-browse/    # 部署产物：releases/ current/ logs/ run/ data/
```

```bash
# 首次部署
sudo apt install -y openjdk-21-jdk-headless git curl
cd ~/source && git clone <仓库地址> novel-browse
cp -r ~/source/novel-browse/scripts ~/source/scripts && cd ~/source/scripts
cp deploy.conf.example deploy.conf   # 可选：改端口 / JVM 参数 / 分支
./deploy.sh                          # 拉码 → 构建 → 发布 → 重启 → 健康检查
./install-service.sh                 # 可选：交给 systemd 托管

# 日常运维
./deploy.sh                 # 部署最新代码（健康检查失败自动回滚）
./deploy.sh --rollback      # 回滚到上一个版本
./app.sh status|restart|log|releases
```

源码目录会自动探测，无需手工配置 `SOURCE_DIR`。详见 [scripts/README.md](scripts/README.md)。

## REST 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/home` | 首页聚合：分类、精选、热门、最近更新、完本佳作 |
| GET | `/categories` | 全部分类 |
| GET | `/categories/{id}` | 分类详情 |
| GET | `/categories/{id}/novels?sort=hot\|update&page=0&size=12` | 分类书库（分页） |
| GET | `/novels?categoryId=&sort=&page=&size=` | 书籍列表，可按分类筛选 |
| GET | `/rank?type=hot\|rating&page=0&size=20` | 排行榜 |
| GET | `/search?q=关键词&page=0&size=12` | 按书名 / 作者 / 标签检索 |
| GET | `/novels/{id}?chapterPreview=10` | 书籍详情 + 首/末章节 + 章节预览 + 同类推荐 |
| GET | `/novels/{id}/chapters` | 全部章节目录 |
| GET | `/novels/{id}/chapter-page?page=0&size=50` | 分页章节目录 |
| GET | `/novels/{id}/chapters/{no}` | 按章节号阅读正文 |
| GET | `/novels/chapters/{chapterId}` | 按章节 id 阅读正文 |
| GET | `/cache` | RocksDB 缓存统计 |
| DELETE | `/cache/{name}` | 清空指定缓存 |
| POST | `/admin/novels`（multipart：`file`、`categoryId`，可选 `status`、`tags`） | 上传 EPUB，自动解析入库 |
| GET | `/admin/novels` | 后台书目列表（按更新时间倒序） |
| DELETE | `/admin/novels/{id}` | 删除书目及其章节 |

统一分页结构：

```json
{ "content": [], "page": 0, "size": 12, "totalElements": 8, "totalPages": 1, "first": true, "last": true }
```

统一错误结构：

```json
{ "timestamp": "2026-10-10T15:36:06.481Z", "status": 404, "error": "Not Found", "message": "小说不存在：id=9999", "path": "/novels/9999" }
```

## 后台管理：EPUB 上传

上传 EPUB 后自动解析并入库，无需手工录入章节：

- **分类 ↔ 磁盘子目录一一对应**：上传时按分类名建子目录，原文件落到 `app.upload.dir/<分类名>/<内容哈希前8位>-<原名>.epub`（默认 `./data/epub`，部署时为 `$APP_ROOT/data/epub`）
- 按内容 SHA-256 去重：同一文件重复上传返回 400；**服务启动时扫描该目录**，未导入的 EPUB 自动入库（已导入的跳过）
- 启动时同步双向映射：子目录名 → 自动创建同名分类（sortNo=999 排最后）；已有分类 → 补齐缺失的子目录。直接放在根目录的 EPUB 归入 `app.upload.default-category-name`（默认 `Imported`）
- 解析：读取 zip → `META-INF/container.xml` 定位 OPF → 按 `spine` 顺序抽取正文章节
- 元数据取自 OPF：`dc:title`、`dc:creator`、`dc:description`、`dc:subject`（作为标签）
- 一个 spine 文档对应一章；单文档内含多个 `h1/h2` 且正文较长时，按标题再切分
- 入库时在同一事务内写入章节数与字数，并让首页等受影响的缓存失效
- 扫描导入的书籍归入 `app.upload.default-category-name`（默认 `Imported`，不存在时自动创建）；删除书目会同时删除磁盘上的 EPUB 原文件

启动日志示例：

目录结构（子目录即分类）：

```text
data/epub/
├── 玄幻奇幻/
│   └── 00acf714-登云帖.epub     ← 上传到「玄幻奇幻」分类
├── 武侠修真/                     ← 手工建的目录，启动时自动建同名分类并导入
│   └── 6e6e83a8-山海拾遗.epub
└── book.epub                    ← 根目录文件，归入「Imported」
```

启动日志示例：

```text
EPUB 上传目录：/root/apps/novel-browse/data/epub
分类目录已创建：武侠修真 → /root/apps/novel-browse/data/epub/武侠修真
目录对应分类已创建：武侠修真（id=8）
启动扫描目录完成：... —— 文件 3 个，新增 1 本，已存在 2 本，失败 0 本，耗时 64 ms
```

```bash
# 上传（categoryId 取自 /categories）
curl -X POST -F "file=@/path/to/book.epub" -F "categoryId=1" \
     -F "status=FINISHED" -F "tags=玄幻,热血" \
     http://localhost:8080/admin/novels

# 配置 app.admin.token 后需带请求头
curl -X POST -H "X-Admin-Token: <token>" -F "file=@book.epub" -F "categoryId=1" \
     http://localhost:8080/admin/novels

curl http://localhost:8080/admin/novels           # 书目列表
curl -X DELETE http://localhost:8080/admin/novels/9
```

安全与限制：`app.admin.token` 留空时不校验（仅适合内网），配置后 `/admin/**` 需带 `X-Admin-Token`；单文件大小上限 `spring.servlet.multipart.max-file-size`（默认 60MB）。当前无账号体系，生产环境应在网关或防火墙层面限制来源。

也可以直接按分类建目录、拷进 EPUB，再重启（或等下次启动），会自动建分类并导入：

```bash
mkdir -p ~/apps/novel-browse/data/epub/武侠修真
cp ~/books/*.epub ~/apps/novel-browse/data/epub/武侠修真/
```

## 书籍封面

封面分两种来源，**接口统一用 `coverUrl` 返回**，前端优先用图片、无图时退回 `coverColor` + `coverMark` 的渐变+首字方案：

| 来源 | 说明 |
| --- | --- |
| EPUB 内嵌封面 | 导入（上传 / 启动扫描）时自动提取：EPUB2 的 `<meta name="cover">`、EPUB3 的 `properties="cover-image"`，再退到 id/href 含 cover 的图片资源；存到 `app.cover.dir`（默认 `./data/covers`，部署时 `$APP_ROOT/data/covers`），文件名 `<内容哈希前8位>-cover.jpg` |

- 访问地址：`GET /covers/<file>`（`WebConfig` 同时映射运行时目录与 classpath，缓存 1 小时）
- EPUB 没有封面时 `coverUrl` 为 null（全局 `non_null`，字段不输出），前端用渐变兜底
- 删除书目会同时删除其封面文件

## MCP（Model Context Protocol）

内置 MCP 服务端（自实现的 JSON-RPC 2.0，无 SDK 依赖），让 AI 客户端直接上传 EPUB、改书目：

```text
POST /mcp    JSON-RPC 2.0（Streamable HTTP，单条 JSON 响应）
GET  /mcp    SSE 心跳流    DELETE /mcp  结束会话（204）
```

| 工具 | 必填 | 可选 | 说明 |
| --- | --- | --- | --- |
| `upload_epub` | `fileName`、`contentBase64` | `categoryId`、`categoryName`、`status`、`tags` | 导入 EPUB：解析章节 + 提取封面 + 落盘，内容哈希重复会拒绝 |
| `update_novel` | `id` | `title`、`author`、`description`、`status`、`tags`、`categoryId` | 更新书目元数据，只改传入字段，并失效相关缓存 |
| `list_categories` | — | — | 查分类 id（上传前用） |
| `list_novels` | — | `limit`（1-100，默认 20） | 查书目 id（更新前用） |

- 鉴权：`app.mcp.token`（留空回退 `app.admin.token`），请求头 `Authorization: Bearer <token>` 或 `X-Mcp-Token`
- `app.mcp.enabled=false` 关闭端点（`/mcp` 返回 404）；`/mcp` 默认免接口签名
- 参数错误、分类/书籍不存在等以 `isError=true` 的 result 返回（附带可选值提示），不是协议级错误

```bash
# 列工具
curl -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 上传（base64）
curl -X POST http://localhost:8080/mcp -H 'Content-Type: application/json' \
     -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"upload_epub",
          "arguments":{"fileName":"book.epub","contentBase64":"<base64>","categoryName":"玄幻奇幻"}}}'
```

客户端配置：`{"mcpServers":{"novel-browse":{"type":"streamable-http","url":"http://<host>/mcp","headers":{"Authorization":"Bearer <token>"}}}}`

## 接口签名

所有接口可开启 HMAC-SHA256 签名校验（`app.signature.*`），**密钥为空时不校验**，本地与前端无需改动。

```text
待签名串：METHOD \n requestURI \n timestamp \n nonce \n bodySha256(小写十六进制)
签名    ：小写十六进制 HMAC-SHA256(secret, 待签名串)
请求头  ：X-App-Key / X-Timestamp（秒）/ X-Nonce / X-Signature
```

- 时间偏差超过 `app.signature.time-window-seconds`（默认 300 秒）拒绝
- `X-Nonce` 只生效一次（存于 `signature-nonce` 缓存，防重放）
- multipart 上传按空 body 哈希签名；`OPTIONS` 预检不校验；`app.signature.exclude-paths` 可配免签路径

```bash
# 生成签名并调用（示例）
ts=$(date +%s); nonce=$RANDOM
body=e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855   # 空 body 的 sha256
sig=$(printf 'GET\n/categories\n%s\n%s\n%s' "$ts" "$nonce" "$body" \
      | openssl dgst -sha256 -hmac "$APP_SIGNATURE_SECRET" -hex | awk '{print $NF}')
curl -H "X-App-Key: $APP_SIGNATURE_KEY" -H "X-Timestamp: $ts" \
     -H "X-Nonce: $nonce" -H "X-Signature: $sig" http://localhost:8080/categories
```

校验失败返回 `401` + `ApiError`（`message` 说明具体原因）。

## 跨域

```yaml
app:
  cors:
    allowed-origins: '*'      # 生产环境请改为前端具体域名，如 https://example.com
    allowed-methods: GET,POST,PUT,DELETE,OPTIONS
    allow-credentials: false
```

## 缓存说明

`rocksdb.cache` 配置项：

```yaml
rocksdb:
  cache:
    enabled: true
    path: ./.rocksdb-cache
    ttl: 10m
    clean-on-start: true   # 内存库每次启动都重新导入，因此默认启动时清空本地缓存
    names: [novel-home-list, novel-detail, novel-chapter, novel-chapter-no, novel-chapter-list]
```

实现要点：所有缓存共享同一个 RocksDB 实例，以 `缓存名:` 作为 key 前缀隔离；条目以 JDK 序列化写入并携带写入时间用于 TTL 过期；反序列化失败或类型不匹配时按未命中处理，业务可用性优先于缓存。接入位置为 service 层（首页列表、书籍详情、章节列表、章节正文）。

## 说明

站点不内置任何书目数据：首次启动后是空的，把 EPUB 放进 `data/epub/<分类名>/`（启动自动扫描导入），或用管理接口 `POST /admin/novels`、MCP 工具 `upload_epub` 导入；分类在导入时按目录名或 `categoryName` 自动创建。仓库内不含任何第三方文本。
