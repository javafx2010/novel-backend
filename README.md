# novel-browse

Java / Spring Boot 编写的在线小说阅读网站后端。**前后端分离**：本工程只提供 REST 接口，不含任何前端页面代码，前端工程（Vue / Vite 等）独立开发部署，通过 CORS 调用本服务。

## 技术栈

| 领域 | 选型 |
| --- | --- |
| 构建工具 | Gradle 9.6.0（Wrapper，发行包走腾讯云镜像） |
| 框架 | Spring Boot 4.1.1（Web / Data JPA / Cache） |
| 数据库 | H2 内存数据库（启动时建表并种子演示数据，进程退出即释放） |
| 缓存 | RocksDB（`rocksdbjni`）本地嵌入式实现，自定义 `CacheManager` |
| 运行时 | JDK 21 |

## 设计文档

完整设计文档见 [`docs/`](docs/README.md)：架构设计、领域模型与数据设计、接口设计、RocksDB 缓存设计、构建部署与配置。

## 目录结构

```
src/main/java/com/novel/browse
├── NovelBrowseApplication.java     启动类
├── cache/                          RocksDB 缓存实现（CacheManager / Cache / 配置 / 序列化）
├── config/WebConfig.java           跨域配置
├── data/DataInitializer.java       演示数据种子（6 分类、8 本书、99 章）
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

统一分页结构：

```json
{ "content": [], "page": 0, "size": 12, "totalElements": 8, "totalPages": 1, "first": true, "last": true }
```

统一错误结构：

```json
{ "timestamp": "2026-10-10T15:36:06.481Z", "status": 404, "error": "Not Found", "message": "小说不存在：id=9999", "path": "/novels/9999" }
```

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
    clean-on-start: true   # 内存库每次启动会重新种子，因此默认启动时清空本地缓存
    names: [novel-home-list, novel-detail, novel-chapter, novel-chapter-no, novel-chapter-list]
```

实现要点：所有缓存共享同一个 RocksDB 实例，以 `缓存名:` 作为 key 前缀隔离；条目以 JDK 序列化写入并携带写入时间用于 TTL 过期；反序列化失败或类型不匹配时按未命中处理，业务可用性优先于缓存。接入位置为 service 层（首页列表、书籍详情、章节列表、章节正文）。

## 说明

站内书目、作者与章节正文均为演示数据（按题材段落池生成），不涉及任何第三方版权内容。
