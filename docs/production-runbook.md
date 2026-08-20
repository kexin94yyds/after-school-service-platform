# 生产运行手册

本手册适用于 Ubuntu 24.04 LTS、Nginx、Java 21、独立 MySQL 8.4 的单应用节点。它不包含 Docker、Kubernetes 或云厂商专属配置。

## 1. 上线判定

仓库测试全绿只证明产物可交付。承载真实学校和未成年人数据前，还必须在预发布主机完成以下环境验证：

- 真实域名和受信 TLS 证书；
- MySQL 8.4 证书身份校验；
- systemd 低权限运行和端口防火墙；
- age 真实加密、异机备份上传和恢复演练；
- 外部告警渠道；
- [check-production.sh](../scripts/check-production.sh) 全部通过。

基线目标为每日两次全量备份、RPO 不超过 24 小时、保留 30 天。备份 timer 在 `00:15` 与 `12:15` 运行，随机延迟最多 15 分钟；健康检查在目标库备份超过 18 小时、缺少或校验失败时告警，给值班人员预留约 6 小时的修复窗口。RTO 目标为 4 小时，但只有在使用接近生产数据量的恢复演练后才能标记为已验证；必须记录备份大小、恢复开始/结束时间、校验结果和演练人员。

## 2. 主机和账号

安装 Java 21、Nginx、MySQL 8.4 客户端、age、curl、jq、rsync、openssl、util-linux 和 ca-certificates。不要使用 MariaDB 客户端代替 MySQL 8.4 客户端。

创建两个无登录账号：

- `after-school`：只运行 Java API；
- `after-school-backup`：只执行备份和健康检查。

准备目录：

```text
/etc/after-school-service/             root-only 配置与凭据
/opt/after-school-service/releases/    root 拥有的不可变 JAR 版本
/opt/after-school-service/tools/       root 拥有的运维脚本
/var/www/after-school-service/releases/ 不可变前端版本
/var/backups/after-school-service/     after-school-backup 私有目录
/var/lib/after-school-service/rectification-materials/ systemd StateDirectory 下的整改附件
```

JAR 和前端发布目录只允许 root 写入。后端服务用户只能读取当前 JAR；备份用户只能写备份目录和自己的 systemd 状态目录。生产 JAR 必须不含 demo 的迁移、profile 配置和刷新组件；`scripts/install-server-release.sh` 会拒绝含任一演示 payload 或以 `-demo.jar` 命名的构件。演示 JAR 只允许在隔离的本机 demo 环境中由 `scripts/run-demo.sh` 使用，禁止复制到生产 release 目录。

## 3. 应用配置与数据库密码

把 [after-school-service.conf.example](../deploy/config/after-school-service.conf.example) 安装到 `/etc/after-school-service/after-school-service.conf`，替换域名、数据库地址和账号，权限设为 `0640 root:root`。

数据库密码单独写入 `/etc/after-school-service/db-password`，权限必须为 `0600 root:root`，文件末尾可以有一个换行。不要在 EnvironmentFile、命令行、JAR、README 或 shell 历史中设置 `DB_PASSWORD`。

生产数据库账号只授予应用所需库的最小 DML/DDL 权限。数据库 URL 必须使用 `sslMode=VERIFY_IDENTITY`，并确保主机名与数据库证书 SAN 匹配。同时必须包含唯一的 `connectionTimeZone=%2B08%3A00` 和 `forceConnectionTimeZoneToSession=true`，使连接会话时区在驱动建立连接时固定为 `+08:00`。

`RECTIFICATION_MATERIAL_DIR` 应保持为 `/var/lib/after-school-service/rectification-materials`。systemd 的 `StateDirectory=after-school-service` 会创建父目录并授予服务账号写权限；不要把附件目录放到不可变 release、Web 根目录或共享临时目录。系统仅接受 PDF/JPG/PNG 且单文件不超过 10 MB，数据库保留对象键、大小、SHA-256、上传人和时间。

监管扫描默认每日 02:00（`Asia/Shanghai`）执行。可在 EnvironmentFile 中用 `SUPERVISION_SCAN_ENABLED`、`SUPERVISION_SCAN_CRON`、`SUPERVISION_SCAN_ZONE` 和 `SUPERVISION_SCAN_STALE_AFTER` 调整；cron 含空格，必须保持引号。只关闭定时任务不会禁用监管员手工扫描。首次发布后应在监管端“扫描记录”确认运行来源、起止时间、成败和失败摘要。

## 4. 首次监管员

只在空生产库首次启动时创建监管员。把一次性密码写入 `/etc/after-school-service/bootstrap-password`，权限设为 `0600 root:root`，并创建临时 systemd drop-in：

```ini
[Service]
LoadCredential=app.bootstrap.password:/etc/after-school-service/bootstrap-password
Environment=APP_BOOTSTRAP_USERNAME=initial_regulator
Environment=APP_BOOTSTRAP_DISPLAY_NAME=初始监管员
```

服务启动并确认账号创建后：

1. 首次登录并立刻修改密码；
2. 删除 drop-in 和 `bootstrap-password`；
3. 执行 `systemctl daemon-reload` 并重启服务；
4. 确认旧会话失效，且服务凭据目录不再包含 `app.bootstrap.password`。

不得让一次性初始化密码长期跟随服务重启。

## 5. systemd 与后端首次发布

把 [after-school-service.service](../deploy/systemd/after-school-service.service) 安装到 `/etc/systemd/system/`。把以下脚本以 root 所有、`0755` 权限安装到 `/opt/after-school-service/tools/`：

- [install-server-release.sh](../scripts/install-server-release.sh)
- [select-server-release.sh](../scripts/select-server-release.sh)
- [install-web-release.sh](../scripts/install-web-release.sh)
- [select-web-release.sh](../scripts/select-web-release.sh)
- [backup-mysql.sh](../scripts/backup-mysql.sh)
- [restore-mysql-backup.sh](../scripts/restore-mysql-backup.sh)
- [monitor-health.sh](../scripts/monitor-health.sh)
- [check-production.sh](../scripts/check-production.sh)

构建 JAR 后使用唯一 release ID 发布：

```bash
release_id="$(date -u +%Y%m%dT%H%M%SZ)"
sudo /opt/after-school-service/tools/install-server-release.sh \
  after-school-service-server/target/after-school-service-server-0.0.1-SNAPSHOT.jar \
  "${release_id}"
```

脚本校验 JAR 与 SHA-256，原子切换 `current`，重启服务并等待 `/readyz`。失败会恢复上一 JAR；首次发布失败会停止服务。

应用级自动回滚不会逆向执行 Flyway。每个迁移必须采用 expand/contract 策略，保证上一 JAR 在新 schema 上仍可运行。涉及删除列、收紧非空或改变字段语义时，必须拆成跨版本迁移并先完成备份和预发布演练。

## 6. 前端与 Nginx

使用与构建基址相同的 release ID：

```bash
npm run build -- --base="/releases/${release_id}/"
sudo /opt/after-school-service/tools/install-web-release.sh \
  after-school-service-web/dist "${release_id}"
```

把 [after-school-service.conf](../deploy/nginx/after-school-service.conf) 安装到 Nginx 配置目录，替换示例域名和证书路径。执行 `nginx -t` 成功后才能重载。

模板假定 Nginx 是第一个可信代理。增加 CDN 或负载均衡时，必须先用 `real_ip` 只信任明确的上游网段，再让限流和转发头使用真实客户端地址。

## 7. 加密备份

使用独立备份账号。把 [mysql-backup.cnf.example](../deploy/config/mysql-backup.cnf.example) 安装为 `/etc/after-school-service/mysql-backup.cnf`，权限设为 `0600 root:root`。该账号至少需要备份对象所需的 SELECT、SHOW VIEW、TRIGGER 和 EVENT 等权限，不要复用应用账号。

在离线管理设备生成 age identity；私钥留在离线设备或受控恢复保管库，只把公钥 recipients 文件复制到服务器：

```bash
age-keygen -o age-identity.txt
age-keygen -y age-identity.txt > age-recipients
```

服务器上的 `/etc/after-school-service/age-recipients` 只能包含公钥。把备份配置、service 和 timer 安装到对应 `/etc/after-school-service/` 与 `/etc/systemd/system/` 路径，创建 `0700 after-school-backup:after-school-backup` 的备份目录，然后启用 timer。

首次上线必须手动执行一次：

```bash
sudo systemctl start after-school-backup.service
sudo systemctl status after-school-backup.service
sudo journalctl -u after-school-backup.service --since today
```

正式公网前必须配置 `BACKUP_UPLOAD_HOOK`，把 `.age` 和 `.sha256` 发送到不同故障域。只有本机备份不满足灾难恢复要求。

## 8. 恢复演练

把目标备份、checksum 和离线 identity 放到隔离恢复主机。使用具备创建新库权限的恢复专用 MySQL defaults 文件：

```bash
/opt/after-school-service/tools/restore-mysql-backup.sh \
  /secure/backup/after_school_service-YYYYmmddTHHMMSSZ.sql.gz.age \
  after_school_restore_drill_YYYYMMDD \
  /secure/restore-mysql.cnf \
  /secure/age-identity.txt
```

目标库必须不存在。脚本会校验 checksum、解密、导入，并确认 Flyway 历史没有失败记录且至少成功应用 V14，确认 17 张核心业务表、报名/计划/通知/整改材料租户外键及 `supervision_alert → supervision_scan_run` 外键均存在，并确认没有预警扫描运行孤儿记录；失败只清理它刚创建的部分库。升级到未来迁移版本后，在恢复演练命令中设置 `RESTORE_REQUIRED_FLYWAY_VERSION` 为当前发布要求的版本，不得降低生产基线。

恢复成功后，用恢复库启动一个不对公网开放的应用实例，执行登录、报名、考勤、请假、监管报表只读抽查，再记录实际 RTO。演练完成前不得把问题状态标为已验证。

## 9. 健康监测与外部告警

安装 healthcheck service、timer 和 [after-school-monitor.conf.example](../deploy/config/after-school-monitor.conf.example)。`MYSQL_DATABASE` 必须与备份 unit 中的目标库一致；timer 每 5 分钟检查服务、数据库 readiness、备份 timer、该目标库备份的新鲜度、相邻 SHA-256 的格式与实际摘要，以及最近一次 backup service 是否失败。因此别的数据库生成的备份、伪造/损坏 checksum 或异机上传 hook 失败都不会被新的无关文件掩盖。`BACKUP_MAX_AGE_HOURS=18` 与每天两次备份一起实现 RPO ≤ 24 小时的可告警缓冲，按 5 分钟检查周期折算约有 6 小时处置时间。

`HEALTH_ALERT_HOOK` 必须是 root 拥有且不可被组或其他用户写入的本地可执行文件。它只接收状态和短消息。接入邮件、企业微信或其他平台后，必须主动制造一次失败并恢复，确认收到一条 FAILED 和一条 RECOVERED。

没有外部告警 hook 时，systemd journal 中虽能看到失败，但不能视为生产告警闭环。

## 10. 日常发布

每次发布依次执行：

1. 确认监控和备份 timer 正常；
2. 手动创建一次加密备份并确认异机上传；
3. 审查 Flyway 是否向后兼容；
4. 运行仓库 [verify.sh](../scripts/verify.sh)，并确认 CI 的后端已知漏洞门禁通过；
5. 发布后端 JAR并等待 readiness；
6. 发布前端不可变版本；
7. 执行生产预检；
8. 观察 journal、错误率和登录限流至少 15 分钟，并确认一次手工监管扫描在运行记录中成功收尾。

CI 使用 `security` Maven profile 执行 OWASP Dependency-Check，CVSS 7.0 及以上已知漏洞会阻断构建；CycloneDX JSON SBOM 在正常 `package` 阶段生成。可在本地复现：

```bash
cd after-school-service-server
NVD_API_KEY='use-a-local-or-ci-secret' mvn clean verify -Psecurity
```

`NVD_API_KEY` 只能放入本地环境或 CI secret，不得写入仓库。漏洞报告位于 `after-school-service-server/target/`；SBOM 位于 `target/classes/META-INF/sbom/application.cdx.json` 并同时嵌入最终 JAR。

不要在发布时清理上一版本。不要在数据库迁移后仅凭 JAR 回滚成功就宣布恢复完成。

## 11. 回滚

后端回滚：

```bash
sudo /opt/after-school-service/tools/select-server-release.sh PREVIOUS_RELEASE_ID
```

前端回滚：

```bash
sudo /opt/after-school-service/tools/select-web-release.sh PREVIOUS_RELEASE_ID
```

回滚后重新运行生产预检。若新版本执行过不兼容迁移，停止自动回滚，按恢复演练流程建立新库或使用已审核的兼容修复版本；禁止手工删除 Flyway 历史行。

## 12. 故障处置

### readiness 失败

检查服务 journal、数据库 TLS/账号、连接池和 Flyway。liveness 正常而 readiness 失败通常表示依赖不可用，不要通过无限重启制造数据库连接风暴。

### 备份失败

检查备份 unit journal、MySQL 8.4 客户端、CA、age recipients、磁盘空间和上传 hook。修复后手动补跑，并验证新的 checksum 与异机对象。

### TLS 或安全头失败

禁止继续重载。先修复证书、域名或 Nginx 模板，`nginx -t` 与生产预检全绿后再恢复流量。

### 数据损坏或误删

立即停止写流量，保留现场和审计记录，不在原库上直接试错。选择故障前备份恢复到新库，完成校验后再切换应用。

## 13. 最终上线门禁

- 仓库完整验收成功；
- Ubuntu 主机生产预检全部通过；
- systemd 服务和两个 timer 已启用；
- 真实域名/TLS/数据库 CA 已验证；
- 24 小时内有本机和异机加密备份；
- 恢复演练成功并记录 RPO/RTO；
- FAILED/RECOVERED 外部告警已实测；
- 负责人确认防火墙不开放 8081/8082；
- 首次监管员凭据已移除并修改密码；
- 迁移和回滚方案已审核。

任一项未满足时，只能继续预发布或受控内网试运行，不能标记为正式生产就绪。

部署完成后用已安装的预检脚本执行最终门禁；Let's Encrypt `live/` 证书链接可直接传入，脚本会跟随它读取当前证书：

```bash
sudo /opt/after-school-service/tools/check-production.sh \
  https://after-school.example \
  /etc/letsencrypt/live/after-school.example/fullchain.pem
```
