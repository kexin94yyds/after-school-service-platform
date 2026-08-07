# 目标

把当前课后服务平台从“业务功能完整、适合演示”提升为基于 Ubuntu 24.04、systemd、Nginx 与独立 MySQL 的可验证生产部署包。

# 已完成

- [x] 完成业务功能、路由与桌面/手机端全链路验收
- [x] 完成生产部署准备度只读审查
- [x] 登记 P-2026-2311
- [x] 锁定推荐部署基线与六阶段写入边界
- [x] 阶段 1：加入真实 readiness/liveness、Prometheus 指标、管理端口隔离与优雅停机
- [x] 阶段 2：补齐 Nginx 登录/API 限流、安全响应头、代理超时与管理端点公网隐藏
- [x] 阶段 3：补齐 systemd 受限托管、数据库凭据隔离、后端不可变发布与应用级自动回滚
- [x] 阶段 4：补齐 age 公钥加密 MySQL 备份、校验、新库恢复与每日 systemd timer
- [x] 阶段 5：补齐 Ubuntu 生产硬门禁、本地健康/备份监测、状态去重与外部告警 hook
- [x] 阶段 6：补齐验收合同、生产 Runbook、前后端原子回滚与全链路验证
- [x] 修复 Actuator 与 Hikari 初始化时序冲突，改由 MySQL JDBC URL 在连接建立时强制 `+08:00` 会话时区
- [x] 通过仓库级全量验证：后端 130 项、前端 59 项、MySQL 8.4.11 迁移/业务链、备份恢复、回滚、监测与数据库断开降级均通过
- [x] 最终只读审查登记 P-2026-2315 至 P-2026-2319
- [x] 实现备份 service 失败监测、Let's Encrypt 链接预检、12 位统一密码策略与交付文档对齐
- [x] 加入 CycloneDX SBOM、OWASP Dependency-Check profile、CI 全量验证与 Dependabot 配置
- [x] 升级 OWASP Dependency-Check 12.2.2 并修复首轮扫描发现的 Tomcat、Jackson、Log4j 和 Swagger UI/DOMPurify 依赖问题
- [x] 无抑制规则完成后端已知漏洞扫描：报告 0 条、高危 0 条
- [x] 完成最终全量验收，并将 P-2026-2315 至 P-2026-2319 更新为已验证解决

# 下一步

仓库内可完成的修复与验收已全部完成。下一步是在真实 Ubuntu 24.04 主机上执行 Nginx/systemd/MySQL TLS/age 恢复演练，并接入真实外部告警渠道。

# 阻塞项

- 外部告警渠道尚未指定；阶段 5 只实现本地检测和明确接入边界
- 没有真实生产服务器、域名、证书、数据库 CA 与密钥，本轮不得伪造环境级验证
- 尚未完成真实 Ubuntu 上的 `nginx -t`、systemd 沙箱、MySQL TLS 证书身份与 age 密钥恢复演练
