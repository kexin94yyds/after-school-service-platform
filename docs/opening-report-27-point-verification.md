# 开题报告 27 项功能验收矩阵

## 验收基线

- 来源：用户提供的《7230264118_贾鑫泽_23软件1_开题报告 (2).doc》
- 角色：学生、家长、教师、教务管理员
- 登录：四个独立登录页面
- 成绩：0–100 分 + 教师文字学习评价
- 验证命令：`./scripts/verify.sh`
- 最终结果：后端 194 项、前端 65 项、桌面/移动 Playwright 10 项、MySQL 8.4.11 填充 V15→V18/V21、空库生产 bootstrap 真实登录、真实 HTTP、2001+ 行独立 XLSX 解析、备份恢复与生产构建全部通过

## 逐项证据

| 项 | 开题报告功能 | 实现证据 | 验证证据 | 结果 |
| --- | --- | --- | --- | --- |
| 1.1 | 四个独立登录页、登录退出改密 | [router/index.ts](../after-school-service-web/src/router/index.ts)、[LoginView.vue](../after-school-service-web/src/views/LoginView.vue)、[AuthController.java](../after-school-service-server/src/main/java/com/afterschool/platform/auth/AuthController.java) | 四入口 E2E、第五角色登录/路由负向、错角色入口、密码修改 HTTP | 通过 |
| 1.2 | RBAC 菜单、接口和数据权限 | [RoleLayout.vue](../after-school-service-web/src/layouts/RoleLayout.vue)、各 Controller 的 `@PreAuthorize`、[CurrentUser.java](../after-school-service-server/src/main/java/com/afterschool/platform/auth/CurrentUser.java) | 四角色越权路由、跨学校/跨学生 HTTP | 通过 |
| 1.3 | 教务维护学生/家长/教师账号、绑定、启停和重置 | [PeopleController.java](../after-school-service-server/src/main/java/com/afterschool/platform/people/PeopleController.java)、[PeopleService.java](../after-school-service-server/src/main/java/com/afterschool/platform/people/PeopleService.java)、[OrganizationPeopleView.vue](../after-school-service-web/src/views/school/OrganizationPeopleView.vue) | 新增账号、历史学生补建账号、绑定和密码测试 | 通过 |
| 2.1 | 学期、课程类别、介绍、年级、上下架 | [TermManagementView.vue](../after-school-service-web/src/views/school/TermManagementView.vue)、[V18 迁移](../after-school-service-server/src/main/resources/db/migration/V18__scope_academic_terms_to_school.sql)、[CourseController.java](../after-school-service-server/src/main/java/com/afterschool/platform/course/CourseController.java) | 同码学期分校创建、跨校更新 404、填充 V15 升级、课程 CRUD HTTP | 通过 |
| 2.2 | 开课计划班数、容量、教师、教室、周期 | [AcademicResourcesView.vue](../after-school-service-web/src/views/school/AcademicResourcesView.vue)、[CourseOfferingView.vue](../after-school-service-web/src/views/school/CourseOfferingView.vue) | 计划明细、开班教师/教室/周期与服务端强约束 HTTP | 通过 |
| 2.3 | 区分课程、计划和实际开班，历史留存 | `course`、`school_service_plan`、`course_offering` 三层模型及状态历史 | MySQL 外键、迁移与重启幂等 | 通过 |
| 3.1 | 教务排课，教师只接收任务 | [TeachingController.java](../after-school-service-server/src/main/java/com/afterschool/platform/teaching/TeachingController.java)、[TeacherSessionsView.vue](../after-school-service-web/src/views/teacher/TeacherSessionsView.vue) | 教务生成课次；教师生成接口 403；教师提交取消状态不会改变排课状态 | 通过 |
| 3.2 | 教师、教室、学生冲突和容量校验 | [CourseService.java](../after-school-service-server/src/main/java/com/afterschool/platform/course/CourseService.java)、[AcademicService.java](../after-school-service-server/src/main/java/com/afterschool/platform/academic/AcademicService.java)、[EnrollmentRuleEngine.java](../after-school-service-server/src/main/java/com/afterschool/platform/registration/EnrollmentRuleEngine.java) | 冲突负向、并发调课和最后名额测试 | 通过 |
| 3.3 | 学生个人课表、教师课表、调课留因 | [StudentScheduleView.vue](../after-school-service-web/src/views/student/StudentScheduleView.vue)、[ScheduleAdjustmentsView.vue](../after-school-service-web/src/views/school/ScheduleAdjustmentsView.vue) | 实际课次、调课/撤销和原因 HTTP | 通过 |
| 4.1 | 学生筛课选课，满额停止 | [StudentEnrollmentView.vue](../after-school-service-web/src/views/student/StudentEnrollmentView.vue)、[EnrollmentService.java](../after-school-service-server/src/main/java/com/afterschool/platform/registration/EnrollmentService.java) | 年级、时间窗、容量、冲突和满额 HTTP | 通过 |
| 4.2 | 学生退选改选并记录日志 | [EnrollmentService.java](../after-school-service-server/src/main/java/com/afterschool/platform/registration/EnrollmentService.java)、`enrollment_action` | 原子改选、`SWITCH_OUT/SWITCH_IN` 和回滚测试 | 通过 |
| 4.3 | 教务报名进度、异常处理、课程/班级名单 | [EnrollmentManagementView.vue](../after-school-service-web/src/views/school/EnrollmentManagementView.vue) | 开班名单 XLSX、行政班名单 XLSX、管理员取消 HTTP | 通过 |
| 5.1 | 学生查看课表、选课、请假、考勤、成绩 | [student](../after-school-service-web/src/views/student) 页面组 | 学生 E2E 与本人数据 HTTP | 通过 |
| 5.2 | 家长只读子女课表、请假、考勤和成绩 | [ParentChildRecordsView.vue](../after-school-service-web/src/views/parent/ParentChildRecordsView.vue) | 家长绑定范围、学生写接口对家长拒绝 | 通过 |
| 5.3 | 家长评价课程和教师 | [EvaluationController.java](../after-school-service-server/src/main/java/com/afterschool/platform/evaluation/EvaluationController.java)、[GuardianEvaluationView.vue](../after-school-service-web/src/views/parent/GuardianEvaluationView.vue) | 双评分、重复评价拒绝和汇总 HTTP | 通过 |
| 6.1 | 教师本人任务、地点、名单及越权拦截 | [CourseService.java](../after-school-service-server/src/main/java/com/afterschool/platform/course/CourseService.java)、[TeachingService.java](../after-school-service-server/src/main/java/com/afterschool/platform/teaching/TeachingService.java) | 其他教师课程访问 403 | 通过 |
| 6.2 | 课次事务、教学记录、教务生成课次 | [TeacherSessionsView.vue](../after-school-service-web/src/views/teacher/TeacherSessionsView.vue)、`lesson_session.notes` | 课次备注、考勤和生成权限 HTTP | 通过 |
| 6.3 | 教师查看选课、出勤、成绩汇总 | [TeacherHomeView.vue](../after-school-service-web/src/views/teacher/TeacherHomeView.vue)、[TeacherGradesView.vue](../after-school-service-web/src/views/teacher/TeacherGradesView.vue) | 教师范围汇总与名单 HTTP | 通过 |
| 7.1 | 学生未来课次请假，家长查看，教师审核 | [LeaveCorrectionController.java](../after-school-service-server/src/main/java/com/afterschool/platform/leavecorrection/LeaveCorrectionController.java)、[StudentAttendanceView.vue](../after-school-service-web/src/views/student/StudentAttendanceView.vue) | 截止时间、本人范围、教师审核、教务审核 403 与撤回 HTTP | 通过 |
| 7.2 | 四种考勤、纠错、教务复核 | [TeachingController.java](../after-school-service-server/src/main/java/com/afterschool/platform/teaching/TeachingController.java)、[LeaveCorrectionService.java](../after-school-service-server/src/main/java/com/afterschool/platform/leavecorrection/LeaveCorrectionService.java) | 四状态、完成锁定、纠错和修订历史 | 通过 |
| 7.3 | 月度考勤、学生家长查看、教务统计 | [EnrollmentController.java](../after-school-service-server/src/main/java/com/afterschool/platform/registration/EnrollmentController.java) 的月度接口 | 学生/家长月度 HTTP 与报表统计 | 通过 |
| 8.1 | 教师录入成绩和学习评价，修改留痕 | [GradeService.java](../after-school-service-server/src/main/java/com/afterschool/platform/grade/GradeService.java)、[V17 迁移](../after-school-service-server/src/main/resources/db/migration/V17__add_student_grades_and_revision_history.sql) | 0–100 校验、越权负向、修改历史 HTTP | 通过 |
| 8.2 | 学生和家长查看本人/子女成绩 | [StudentGradesView.vue](../after-school-service-web/src/views/student/StudentGradesView.vue)、[ParentChildRecordsView.vue](../after-school-service-web/src/views/parent/ParentChildRecordsView.vue) | 学生本人和家长绑定范围 HTTP | 通过 |
| 8.3 | 教务汇总成绩分布、课程/教师评分和意见 | [SchoolGradesView.vue](../after-school-service-web/src/views/school/SchoolGradesView.vue)、现有评价汇总 | 成绩分布、双评分、家长意见 HTTP 与浏览器 E2E | 通过 |
| 9.1 | 教务以选课、开班、冲突、考勤、成绩、评价监管 | 教务工作台、报表、成绩统计、审计页面 | 多维 HTTP 汇总与数据范围 | 通过 |
| 9.2 | 图表和 Excel | [ReportBarChart.vue](../after-school-service-web/src/components/ReportBarChart.vue)、课程/名单/成绩 XLSX 接口、[独立 OOXML 解析器](../scripts/VerifyXlsx.java) | ECharts 生产构建、所有 XLSX 独立解析、成绩 2001+ 行 | 通过 |
| 9.3 | 排课、选课、考勤纠错和成绩修改留痕 | [OperationAuditFilter.java](../after-school-service-server/src/main/java/com/afterschool/platform/audit/OperationAuditFilter.java)、专项动作/修订表 | 审计元数据、报名动作、考勤修订、成绩修订 HTTP | 通过 |

## 结论

开题报告 9 个模块、27 个子项均已实现并通过自动化、真实数据库、真实 HTTP、浏览器和产物级验证。旧监管预警与整改数据结构继续保留作历史兼容，不进入新版学生、家长、教师、教务管理员四角色登录主流程。
