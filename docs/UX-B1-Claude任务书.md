# B1 后端实施任务书

你是本批唯一产品代码写入者。主助手已完成需求设计，会独立验收。工作目录 E:/vibcoding/proj-timeline-ux-20261105，基线 4219bb5，feature/ux-review-20261105。

必读 AGENTS.md、PROJECT_MEMORY.md、docs/复用方法与设计模式.md、docs/UX改造-2026-11-05.md、相关接口契约、后端代码及测试。原报告 docs/UX原始报告-2026-11-05.md。本轮新的决定覆盖旧的日期必填、要求开工后完全冻结等对应限制，未提及的原边界保持。

只实施 B1，允许修改 backend/src/main/java 的受影响业务类、backend/src/test/java、追加 V17 迁移、docs/database.sql、docs/接口设计.md、docs/复用方法与设计模式.md。禁止写前端、PROJECT_MEMORY、PLAN、原报告和本任务书；不改 V1–V16，不读 .env/凭据/数据目录，不执行命令、不启动服务、不提交 Git。工具只有 Read/Edit/Write/Glob/Grep。

固定契约：

- nodes.plan_start / plan_end / owner_id 可 null；新增 historical boolean default false。Node 返回 ownerId/ownerName null 允许、historical 字段、planningStatus 派生 UNSCHEDULED(无planEnd)/SCHEDULED；DONE/CANCELLED 可带排期字段但待排期统计仅开放项。所有 JOIN/Java拆箱/flags/权限/Bug默认负责人/人员负载处理空值，不能让无负责人项消失或500。日期各自可空，两者都有时有序。owner 未传沿默认行为、显式 null 可清空；update 未传任一字段保留原值。标题仍必填、长度受控。
- POST /projects/{id}/nodes：只标题可建；historical=true 允许 status IN_PROGRESS/DONE + actualStart/actualEnd；DONE actualEnd必填，actualStart可空；IN_PROGRESS actualStart必填且actualEnd空；所有实际日期 <= LocalDate.now(clock)，有序。历史模式需 PM/ADMIN，普通模式不能借 status/实际日期绕过状态流程。要求有负责人才能开始普通项；没有planEnd也允许开始（表示未承诺交付日期）。
- PUT /nodes/{id}：字段可局部更新，清空日期/负责人只允许开放未开始项或历史已完成的未知负责人；进行中负责人不可清空。标题/说明修订保留审计；不要由此改变 module/status/actual dates。历史日期单独接口。
- POST /nodes/{id}/reopen {expectedStatus:'DONE',reason}：仅 PM/ADMIN，DONE->IN_PROGRESS，actualEnd清空，actualStart保留。用 STATUS_CHANGE 日志（现有enum）记录完整原值与原因；锁+受影响行数检查。无负责人历史项须先指定有效负责人再重开。
- PUT /nodes/{id}/actual-dates {actualStart:null|string,actualEnd:null|string,expectedStatus,reason}：PM/ADMIN；仅 IN_PROGRESS/DONE；DONE必须actualEnd，进行中不能actualEnd；日期校验同历史。纠错写既有 RESCHEDULE 日志，保留前后状态/日期及原因，更新 historical=1。不改 createdAt。
- POST /nodes/{id}/move {moduleId:null|positive,expectedModuleId:null|positive,reason}：必须显式expectedModuleId，源requireNodeManager+目标requireModuleManager或项目级requireProjectManager；同项目，锁后复核身份；只更新module_id，不改负责人或关联；历史日志 RESCHEDULE 完整前后归属。关闭项也允许整理。
- 进行中要求的结构变更需要 changeReason，新增/编辑请求体带changeReason，DELETE /deliverables/{id}?reason=...。复用现有日志动作，新增/修改/删除保存完整前后快照+原因，包含附件id、负责人、备注、排序、名称、状态。未开始不需原因；关闭项原限制保持。无需单建版本表，日志为追加历史。开始动作日志包含交付要求基线完整快照。
- notifications 默认忽略 current actor（无SecurityContext时系统通知可用）；工作项/Bug/要求相关通知清楚包含项目名、工作名、操作者、动作。沿用type/link，不新增枚举/不清旧通知。
- Project stats 加 unscheduled/unassigned/emptyModules；原total/done含待排期但不含取消。周报按实际完成日期已有行为保留，纠错后未发布重新计算，已发布快照不改。
- 保持响应封装/错误码；非法输入400、无权403、并发/状态409；新增API写接口文档。共享项同步登记。

需要正式回归：标题创建+默认负责人/显式null、无计划不延期且列表不丢；历史日期/周报归周/未来日期拒绝；重开权限/原因/并发及原历史保留；移动权限/跨项目/expectedModuleId/关联不变；要求结构修改缺理由409与有理由成功/历史快照；self通知被跳过及他人通知含名称；空负责人关联Bug须明确传assignee或清晰400，绝不500。已有测试因契约变化需要合理更新但保留受限侧验证。

完成返回文件清单、准确API字段、已做及未做验证、风险。不用运行构建，主助手会执行。结果写 docs/UX-B1-Claude交付.md（额外允许）。完成停止。
