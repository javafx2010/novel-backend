@@LOG_DIR@@/*.log {
    daily
    rotate 14
    missingok
    notifempty
    compress
    delaycompress
    copytruncate
    create 0644 @@RUN_USER@@ @@RUN_GROUP@@
}
