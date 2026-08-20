# 中小学课后服务选课与教务监管平台

一个面向毕业设计答辩、可运行且可一键验收的课后服务选课与教务监管系统。平台覆盖监管部门、学校管理员、教师和家长四类角色，贯通“学期规划—学校备案—课程排班—学生选课—请假考勤—纠错留痕—预警整改—课程评价—监管分析—操作审计”完整业务链。

## 技术栈

- 后端：Java 21、Spring Boot 4、Spring Security、MyBatis、Flyway
- 数据库：MySQL 8.4
- 前端：Vue 3、TypeScript、Vite、Pinia、Element Plus、ECharts
- 认证：服务端 Session、HttpOnly 会话 Cookie、Cookie/Header CSRF

## 已实现范围

| 角色 | 主要能力 |
| --- | --- |
| 监管人员 | 管理监管/学校管理员账号与负责学校、维护学期、审核含课程规模和师资明细的服务计划、接收监管通知、下发整改、复核材料、查看跨校分析与审计 |
| 学校管理员 | 管理本校组织人员、服务计划明细、教室校历、课程 Excel 导入导出、课程开班、报名名单、考勤、整改材料、统计和审计 |
| 教师 | 仅查看本人开班与课次，生成课次、登记考勤、审核本人课程请假、申请已完成考勤纠错 |
| 家长 | 仅查看绑定学生，选课/退选/原子改选、查看逐课次与月度考勤、按未来课次请假、分别评价课程和教师 |

主要业务闭环：

- 学校服务计划按 `草稿 → 已提交 → 已备案 → 已生效 → 已结束 → 已归档` 流转；提交前必须填写课程类型、计划课程/开班数、单班规模和师资人数。
- 开班发布必须关联标准学期、已备案计划和启用教室；后端与选课规则双重拒绝未备案开课，并校验教室容量、教师/教室/学生冲突。
- 家长在开课前提交请假，批准结果自动预填考勤名单。
- 已完成课次的考勤禁止直接覆盖；教师发起纠错、学校审批后，原值和新值写入不可变修订历史。
- 监管扫描覆盖超额开班、师资不足、考勤缺失、未备案开课，并保留未生成课次和低出勤率增强规则；默认每日 02:00 自动执行，向负责学校的监管账号生成站内通知。
- 监管人员下发限期整改通知，学校上传 PDF/JPG/PNG 材料后才能提交复核；通知、材料版本、SHA-256、处理动作和关闭/退回全过程留痕。
- 课程、选课名单、课程绩效和违规整改均支持真实 XLSX；课程还支持模板化批量导入，导入文件限制行列、大小和 XML/ZIP 安全边界。
- 所有成功写操作保存去敏审计元数据，不记录请求正文或密码；家长评价在监管端自动去除学生、家长和评论明细。

即时选课会同时校验：

- 报名时间窗口
- 课程启用状态，以及首个未取消实际课次尚未开始（尚未生成课次时回退到开班排期）
- 学生状态与家长绑定关系
- 课程适用年级
- 重复报名
- 学生上课时间冲突
- 剩余容量

最后一个名额由数据库事务、固定加锁顺序和原子容量更新共同保护。

学校管理员可在本校范围内纠正并取消单条报名；取消人和取消时间会保存在报名记录中。课次开始前不能登记考勤，提交时必须覆盖该课次的完整有效名单，完成后的考勤会锁定，避免无痕覆盖历史数据。

## 一键验收

环境要求：

- JDK 21
- Maven
- Node.js `^22.18.0` 或 `>=24.11.0`
- MySQL 8.4 安装在 `/opt/homebrew/opt/mysql@8.4`，或通过 `AFTER_SCHOOL_MYSQL84_HOME` 指定
- `curl`、`jq`、`lsof`、`rsync`
- Playwright Chromium，或本机 Google Chrome；无系统 Chrome 时首次执行 `npx playwright install chromium`

执行：

```bash
./scripts/verify.sh
```

验收脚本会自动：

1. 运行后端测试，生成不含演示迁移的生产可执行 JAR、独立 demo JAR 和 CycloneDX JSON SBOM。
2. 运行前端 Vitest、TypeScript 检查、生产构建、高危依赖审计，以及 Chromium 桌面/移动端 Playwright 浏览器验收。
3. 在临时目录启动独立 MySQL 8.4，默认使用 `18306`，不会连接或修改本机 `3306` 数据库。
4. 在空库执行生产迁移至 V14，再补入演示 V4/V4.1/V8/V11/V15，并验证 32 张业务表及新增计划、通知、报名历史和整改材料外键。
5. 通过真实 HTTP Session 和 CSRF 跑通四角色权限、监管账号开户注册、计划明细与备案强约束、Excel 导入导出、原子改选、月度考勤、四类预警、通知、整改附件、双维评价和多维报表。
6. 重启后端验证 Flyway 幂等性，退出时清理临时数据库和进程。

如默认验收端口被占用，可覆盖：

```bash
AFTER_SCHOOL_VERIFY_SERVER_PORT=19081 \
AFTER_SCHOOL_VERIFY_MANAGEMENT_PORT=19082 \
AFTER_SCHOOL_VERIFY_MYSQL_PORT=19306 \
AFTER_SCHOOL_VERIFY_WEB_PORT=16174 \
./scripts/verify.sh
```

## 本地运行

### 1. 准备 MySQL 8.4

在一个 MySQL 8.4 实例中创建本地数据库和最小权限账号：

```sql
CREATE DATABASE after_school_service
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE USER 'after_school'@'127.0.0.1'
  IDENTIFIED BY 'replace-with-a-local-password';

GRANT ALL PRIVILEGES ON after_school_service.*
  TO 'after_school'@'127.0.0.1';
```

### 2. 启动后端

生产 JAR 默认不包含 demo 的迁移、配置或刷新组件，不能通过单独设置 `demo` profile 获得或改写演示数据。演示必须构建并通过专用启动器运行独立的 demo JAR：它只会连接本机回环地址的专用 `after_school_demo` 数据库，并在启动前清除继承的 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 与 Spring 数据源覆盖。先创建可随时删除的本机数据库和专用账号：

```bash
mysql -u root -p <<'SQL'
CREATE DATABASE after_school_demo
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
CREATE USER 'after_school_demo'@'127.0.0.1'
  IDENTIFIED BY 'replace-with-a-demo-password';
GRANT ALL PRIVILEGES ON after_school_demo.*
  TO 'after_school_demo'@'127.0.0.1';
FLUSH PRIVILEGES;
SQL

cd after-school-service-server
mvn -B package -Pdemo-artifact
cd ..

DEMO_DB_PASSWORD='replace-with-a-demo-password' \
  ./scripts/run-demo.sh \
  after-school-service-server/target/after-school-service-server-0.0.1-SNAPSHOT-demo.jar
```

可用 `DEMO_MYSQL_PORT`、`DEMO_DB_USERNAME` 和 `DEMO_MANAGEMENT_PORT` 覆盖本机 demo 数据库端口、账号和管理端口；不可用 `DB_URL`、`DB_USERNAME` 或 `DB_PASSWORD` 覆盖 demo 数据源。启动器把应用和管理端点固定在 `127.0.0.1`，默认分别使用 `8081`、`8082`。不得把 `-demo.jar` 交给生产发布脚本。

### 3. 启动前端

```bash
cd after-school-service-web
npm ci
VITE_API_PROXY_TARGET=http://localhost:8081 npm run dev
```

访问 `http://localhost:5173`。

## 演示账号

以下账号只在按上述方式构建并启动的独立 demo 构件中存在，统一密码为 `123456`：

| 角色 | 用户名 |
| --- | --- |
| 监管人员 | `admin` |
| 学校管理员 | `school_admin` |
| 教师 | `teacher_wang` |
| 家长 | `parent_chen` |
| 第二所学校管理员 | `school_admin_2` |
| 第二所学校教师 | `teacher_li` |
| 第二所学校家长 | `parent_zhao` |

所有学校、姓名、电话和编号均为虚构数据。

## 数据库迁移

- `db/migration/V1`：13 张组织、人员、课程、报名与教学核心表
- `db/migration/V2`：四类角色参考数据
- `db/migration/V3`：跨租户复合外键、考勤关联与并发索引加固
- `db/demo/V4`：可选演示学校、账号与基础工作流数据
- `db/demo/V4.1`：为“先 V14、后启用 demo”的数据库预先补齐 V8 固定预警的扫描运行父记录
- `db/migration/V5`：学期、服务计划、教室、校历、调课与资源冲突约束
- `db/migration/V6`：请假、考勤纠错与不可变修订历史
- `db/migration/V7`：监管预警、整改历史、操作审计与课程评价
- `db/demo/V8`：可选的全面版毕业设计演示状态与待办数据
- `db/migration/V9`：把监护历史引用与当前有效授权解耦
- `db/migration/V10`：报名取消联动终止有效请假，同时保留既有审核轨迹
- `db/demo/V11`：把演示监管账号调整为 `admin`，并统一使用便于答辩演示的简短密码
- `db/migration/V12`：新增监管扫描运行历史、计划任务系统操作人与跨实例重入防护
- `db/migration/V13`：回填所有历史预警的扫描运行父记录，并以外键约束 `supervision_alert.scan_run_id`
- `db/migration/V14`：计划明细、监管负责学校与通知、报名动作历史、整改通知/附件、双维评价和四类指定预警
- `db/demo/V15`：为演示开班补齐标准学期、备案计划、教室、计划明细、监管范围和双维评价数据

默认和 `prod` 模式只加载生产迁移，在空库上执行至 V14，不创建任何学校、人员或演示账号；两种模式保持 Flyway 严格顺序。常规构建产物在归档层再次排除 demo 迁移、profile 配置和刷新组件。

`demo-artifact` profile 额外加载 V4、V4.1、V8、V11、V15，并且仅在 demo JAR 的 `demo` profile 中开启 Flyway out-of-order：同一数据库即使先迁移到 V14，仍可补入虚构演示数据和开题报告完整业务关联。演示迁移只能写入可随时丢弃的本机 `after_school_demo` 库。

## 生产部署（同源 Nginx）

生产环境由 Nginx 统一对外提供 `https://after-school.example`：`/` 服务 Vue SPA，`/api/` 保留完整 URI 后代理到仅监听本机的 Spring Boot。前端代码使用相对基址 `/api`，因此生产构建时不需要设置 `VITE_API_PROXY_TARGET`；该变量只服务于 Vite 本地开发服务器。

正式部署基线为 Ubuntu 24.04、systemd、Nginx、Java 21 和独立 MySQL 8.4。完整的账号、目录、凭据、发布、回滚、备份恢复、监控告警和上线门禁见 [`docs/production-runbook.md`](docs/production-runbook.md)。仅完成仓库构建不等于真实环境已经生产就绪。

`prod` 默认只绑定 `127.0.0.1`，启用 Servlet 容器的 native 转发头处理，仅把 loopback 识别为内部代理，并始终为会话 Cookie 设置 `Secure`。Nginx 模板会在第一个可信边界把 `X-Forwarded-For` 覆盖为实际连接地址，不会把客户端自带的同名头追加到后端。这个信任边界的前提是只有同机 Nginx 能连接后端：不要将 Spring Boot 端口直接暴露到局域网或公网；如果前面另有负载均衡或 CDN，必须先用 Nginx `real_ip` 明确列出可信来源，再继续覆盖转发头。

### 1. 构建后端与前端

```bash
cd /path/to/after-school-service

cd after-school-service-server
mvn -B clean verify
cd ../after-school-service-web
npm ci
release_id="$(date -u +%Y%m%dT%H%M%SZ)-${RANDOM}"
npm run build -- --base="/releases/${release_id}/"
cd ..
```

构建完成后，可执行 JAR 位于 `after-school-service-server/target/`，前端唯一需要发布的内容是 `after-school-service-web/dist/`。

### 2. 安装前端产物

以下目录是 Nginx 专用静态根目录，不要将源码、`.env`、数据库脚本或服务器配置复制进去。安装脚本先把完整产物写入不可变版本目录，确认成功后再原子切换 `current` 符号链接；部署过程中不会出现新 HTML 引用尚未到位资源的窗口：

```bash
sudo ./scripts/install-web-release.sh \
  after-school-service-web/dist \
  "${release_id}"
```

生产构建的 Vite 基址包含同一个 `release_id`，因此已打开页面后续懒加载仍请求原版本 URL。安装脚本默认保留旧版本 7 天，再只清理超过宽限期且不是当前版本的目录；需要更长的客户端停留窗口时，可用第四个参数提高天数。新页面和 SPA 路由响应带 `Cache-Control: no-cache`，版本化哈希资源则可长期缓存。不要绕过脚本用 `rsync --delete` 原地覆盖 `current` 或 `releases`。

### 3. 安装并验证 Nginx

仓库内的 [`deploy/nginx/after-school-service.conf`](deploy/nginx/after-school-service.conf) 是可直接替换参数的 HTTPS 模板。它包含原子版本入口、旧版本资源宽限、HTML 重验证、SPA `try_files` 回退、`/api/` 原样代理、反向代理头和内部文件防护；当前系统没有 WebSocket 端点，不需要 `Upgrade` 配置。

```bash
sudo install -m 0644 \
  deploy/nginx/after-school-service.conf \
  /etc/nginx/conf.d/after-school-service.conf

# 编辑已安装的文件：替换 after-school.example，并指向真实证书和私钥。
sudoedit /etc/nginx/conf.d/after-school-service.conf

# 只有语法和证书检查成功后才重载。
sudo nginx -t
sudo systemctl reload nginx
```

Nginx 应直接终止 HTTPS，且 `80` 端口只跳转到 HTTPS。模板中 `proxy_pass http://127.0.0.1:8081;` 末尾故意没有 `/`，否则 Spring Boot 收到的 `/api/...` 前缀会被删除。

### 4. 启动后端

生产环境不直接从交互 shell 长期运行 JAR。安装 [`deploy/systemd/after-school-service.service`](deploy/systemd/after-school-service.service)，从 [`deploy/config/after-school-service.conf.example`](deploy/config/after-school-service.conf.example) 创建非秘密 EnvironmentFile，并把数据库密码放入 systemd root-only credential。后端版本通过 [`scripts/install-server-release.sh`](scripts/install-server-release.sh) 安装到不可变目录；readiness 失败时恢复上一 JAR。

`APP_CORS_ALLOWED_ORIGIN` 必须是用户在浏览器中访问的唯一公网 Origin，即“协议 + 域名 + 非默认端口”，不带路径或末尾 `/`。不要填写 `http://127.0.0.1:8081`。防火墙必须保持应用端口 `8081` 和管理端口 `8082` 不对局域网或公网开放。

初始监管员通过一次性 systemd credential 创建，成功登录并修改密码后立即删除 credential 和 drop-in；不要把初始化密码保留在环境文件中。

### 5. 验证同源交付与运行状态

```bash
# 应返回 308，Location 指向同域名 HTTPS。
curl -I http://after-school.example/

# SPA 首页和深层路由均应返回 index.html。
curl -fsS https://after-school.example/ >/dev/null
curl -fsS https://after-school.example/parent/enrollments >/dev/null

# API 通过同一 Origin 访问，不暴露 8081。
curl -fsS https://after-school.example/api/public/system-info | jq -e '.status == "ready"'

# 防误配检查：隐藏文件必须被拒绝。
test "$(curl -sS -o /dev/null -w '%{http_code}' https://after-school.example/.env)" = '403'
```

`/api/public/system-info` 只说明 HTTP 应用可响应。真实运行状态使用仅本机可达的 `/livez`、`/readyz` 和 `127.0.0.1:8082/actuator/prometheus`；readiness 包含数据库检查。部署完成后必须在服务器执行 [`scripts/check-production.sh`](scripts/check-production.sh)，它会验证系统版本、systemd、端口、TLS、安全头、CORS、指标和备份。

`prod` 模式严格解析并要求唯一的 `sslMode=VERIFY_IDENTITY`、`connectionTimeZone=+08:00` 和 `forceConnectionTimeZoneToSession=true`，默认启用 Secure 会话 Cookie，并关闭 OpenAPI 页面。应用会按“账号 + 来源地址”对连续登录失败执行指数退避，Nginx 同时提供登录/API 两级限流和安全响应头。数据库密码和初始监管员密码不得写入仓库。首次登录后可从右上角修改密码；修改或重置密码会使旧会话失效。

### 6. 备份、监控与回滚

[`scripts/backup-mysql.sh`](scripts/backup-mysql.sh) 把 MySQL 8.4 在线导出直接压缩并用 age 公钥加密，明文 SQL 不落盘；[`scripts/restore-mysql-backup.sh`](scripts/restore-mysql-backup.sh) 只允许恢复到尚不存在的新库，并要求成功 Flyway 历史至少包含 V14、17 张核心业务表、关键租户/监管/整改材料外键和零条预警扫描运行孤儿记录。systemd timer 在每日 `00:15` 和 `12:15` 各运行一次，最多随机延迟 15 分钟；健康检查每 5 分钟验证 `MYSQL_DATABASE` 对应备份的 SHA-256 和新鲜度，18 小时未成功即告警，为 RPO 不超过 24 小时预留约 6 小时的修复窗口。

正式公网前必须完成一次真实恢复演练、配置异机备份上传和外部 FAILED/RECOVERED 告警。后端用 [`scripts/select-server-release.sh`](scripts/select-server-release.sh) 回滚，前端用 [`scripts/select-web-release.sh`](scripts/select-web-release.sh) 选择旧版本。JAR 回滚不等于数据库迁移回滚；Flyway 变更必须向后兼容。

仓库的 [CI 工作流](.github/workflows/verify.yml) 会重跑全量隔离验证，并使用 OWASP Dependency-Check 在 CVSS 7.0 及以上阻断后端已知漏洞。本地可在后端目录执行 `NVD_API_KEY='...' mvn clean verify -Psecurity`；NVD API key 只允许放在本地环境或 CI secret。

## API 与目录

开发模式下可访问：

- OpenAPI JSON：`http://localhost:8081/v3/api-docs`
- Swagger UI：`http://localhost:8081/swagger-ui/index.html`

目录：

```text
after-school-service-server/  Spring Boot API、MyBatis 映射、Flyway 迁移
after-school-service-web/     Vue 3 管理端与家长端
deploy/nginx/                 同源 HTTPS 生产交付模板
deploy/systemd/               后端、备份与健康检查 systemd 单元
deploy/config/                无真实密钥的生产配置示例
docs/production-runbook.md    Ubuntu 生产运行、恢复和故障处置手册
scripts/verify.sh             隔离数据库的一键全链路验收
```

## 当前范围边界

当前版本不包含学生独立登录、支付、微信小程序/原生 App、AI 推荐、短信/微信等外部消息渠道和审批式候补队列；监管站内通知已包含在平台内。
