[Unit]
Description=@@APP_NAME@@ (Spring Boot)
Documentation=file://@@SOURCE_DIR@@/README.md
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=@@RUN_USER@@
WorkingDirectory=@@APP_ROOT@@
# 运行期可调参数：JAVA_OPTS / SPRING_PROFILES_ACTIVE（修改后需 systemctl restart）
EnvironmentFile=-@@APP_ROOT@@/app.env
ExecStart=@@JAVA_BIN@@ ${JAVA_OPTS} -DLOG_DIR=@@LOG_DIR@@ -jar @@CURRENT_LINK@@/app.jar --server.port=@@PORT@@ --rocksdb.cache.path=@@CACHE_DIR@@ --app.upload.dir=@@UPLOAD_DIR@@ --app.cover.dir=@@COVER_DIR@@ @@EXTRA_ARGS@@
SuccessExitStatus=143
Restart=on-failure
RestartSec=5
StandardOutput=append:@@LOG_FILE@@
StandardError=append:@@LOG_FILE@@
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
