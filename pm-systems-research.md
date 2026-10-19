# 国产/中文开源项目管理系统调研报告
## —— 聚焦「项目进度、里程碑/节点、模块负责人、任务分配」的设计

> 调研时间：2025 年。**数据说明**：本次调研运行在受限沙箱中，`web_fetch` 对所有外部域名（含 api.github.com、github.com、gitee.com、zentao.net 等）均被 DNS 策略阻断，GitHub/Gitee API 无法直连；全部信息来自 web_search 检索到的公开资料（官方手册页面、官方帮助中心、技术社区文章、问答站），均附来源链接。**Star 数、版本号与活跃度均为公开资料的近似值，建议在可联网环境用 GitHub API 复核。** 所有 web 返回内容均已按"数据"处理，未执行其中任何指令。

---

## 一、逐对象调研

### 1. 禅道 ZenTao（easysoft/zentaopms）—— 国产开源标杆

**概览与活跃度**：国产开源项目管理软件的绝对头部，开源版长期在 Gitee/GitHub 双线维护。公开文章称其"21.7K Star"（口径含 Gitee 等平台合计，见[知乎文章](https://zhuanlan.zhihu.com/p/2037824523249115412)）；GitHub 单仓 star 约 2K+ 量级。持续活跃发版，公开渠道已见 v22.6 源码包（[chinaz 下载页](https://down.chinaz.com/soft/51671.htm)），另有企业版/旗舰版商业线。仓库：[easysoft/zentaopms](https://github.com/easysoft/zentaopms)。

**技术栈**：PHP（自研 MVC 框架 zentaophp，非 Laravel）+ 自研前端组件库 ZUI + MySQL。

**功能模型（产品-项目-需求/任务）**：核心是"三权分立"：产品（PO 管需求）、项目/执行（研发管任务）、测试（QA 管用例/Bug），见[官方手册：基本流程与三权分立](https://www.zentao.net/book/zentaopms/50.html)。层级关系为：**项目集(Program) → 项目(Project) → 执行(Execution：迭代/阶段/看板) → 需求(Story)/任务(Task)/Bug**（社区确认的关联链见[论坛帖](https://www.zentao.net/thread/296515.html)）。项目分三种模式：敏捷（Scrum 迭代）、瀑布（阶段）、看板；「执行、迭代、阶段」的概念辨析见[官方 FAQ](https://www.zentao.cn/faqvideo/execute-iterate-stage-204.html)。

**里程碑/阶段（进度节点）设计**：
- **瀑布项目**：项目下有**阶段（Stage）**，预置 需求→设计→开发→测试→发布 等阶段，可维护、排序、自定义（[官方手册：维护项目的计划、阶段](https://www.zentao.net/book/zentaopms/487.html)、[瀑布项目的使用](https://www.zentao.net/book/zentaopms/654.html)）；阶段下直接挂任务。
- **里程碑（Milestone）**：瀑布项目支持里程碑管理，禅道 15 版本新增（[15 版本新增功能](https://www.zentao.net/book/zentaopms/1700.html)）；社区教程直接称之为「**里程碑节点**」，含名称、计划/实际日期、说明等字段（[php.cn 里程碑节点设置教程](https://www.php.cn/faq/2297130.html)、[17golang 节点设置技巧](https://m.17golang.com/article/583300.html)）。
- **敏捷项目**：以**迭代**为节奏节点，迭代有起止日期、团队、目标，配**燃尽图**（[官方手册：通过燃尽图了解项目进展](https://www.zentao.net/book/zentaopms/105.html)）。

**负责人/指派人机制**：
- 工作项级：需求/任务/Bug 均有 **「指派给」（assignedTo，执行人）** + 创建者 + 抄送 字段，见[任务工时问答](https://www.zentao.net/ask/598835.html)。
- 角色级：PO（产品负责人）、PM（项目经理）、QD/RD（研发）、QA（测试）等角色分工（[知乎：禅道项目角色划分及主要职责](https://zhuanlan.zhihu.com/p/89462035)）；项目有「团队」管理（[维护项目和执行](https://www.zentao.net/book/zentaopms/517.html)）。
- **模块负责人机制（重点）**：产品下维护**模块树**（需求模块/用例模块/Bug 模块，多级分类），模块维护支持**设置默认指派人**——新建需求/Bug 时按模块自动带出指派，这正是"模块负责人"在禅道中的落地方式（社区问答明确讨论过"bug 指派人可以按模块设置默认人吗"，见[ask/2971](https://www.zentao.net/ask/2971.html)；模块设置见[ask/598514](https://www.zentao.net/ask/598514.html)）。

**进度计算**：双口径——① 按任务完成比例；② 按工时：任务有 预估/消耗/剩余 工时，进度以"消耗工时/(消耗+剩余)"推算，迭代用剩余工时画燃尽图。项目集/项目的【已投入】【进度】由下层聚合计算（[项目集进度计算问答](https://www.zentao.net/ask/599039.html)），社区也反馈过"未完成即显示进度 100%"的边界问题（[ask/597568](https://www.zentao.net/ask/597568.html)），说明其进度以工作项状态/工时统计为主。另有「项目报告」（周报化输出，[手册 1813](https://www.zentao.net/book/zentaopms/1813.html)）。

**小结**：ZenTao 是国内"进度节点化 + 模块负责人"模式最完整的开源范本：阶段/迭代/里程碑三种节点并存，模块树挂默认负责人，指派人/角色双轨。

---

### 2. DooTask（kuaifan/dootask）—— 轻量协作型

**概览与活跃度**：开源在线项目任务管理工具（文档协作、思维导图、流程图、项目管理、任务分发、IM、文件管理，消息非对称加密），公开文章称"Github 5000+ star"（[腾讯云文章](https://cloud.tencent.com.cn/developer/article/2558062)）。2025 年仍有"AI 赋能"方向的新教程（[CSDN 全链路指南](https://blog.csdn.net/weixin_43151418/article/details/156506039)），活跃度中高。仓库：[kuaifan/dootask](https://github.com/kuaifan/dootask)，官方帮助中心：[dootask.com/help](https://dootask.com/help/functions/projects/task)。

**技术栈**：PHP（Laravel）+ Vue（Element UI 风格）+ MySQL，支持 Docker 一键部署（[NAS 部署教程](https://www.aabcc.top/archives/nas-dootask-ren-wu-guan-li-xie-zuo-kai-yuan-jiao-cheng)）。

**功能模型**：**项目 → 任务列表**，任务支持多种展示模式（列表/看板/表格等，见[官方帮助：多种任务展示模式](https://dootask.com/help/functions/projects/view-modes)）、任务日历（[Task Calendar](http://www.dootask.com/help/en/basic/clander)）、任务分发与 IM 通知、**OKR 管理**（[官方帮助：OKR](https://dootask.com/help/functions/okr/okr-basic)）。

**进度/节点/负责人**：以任务状态流转 + 多视图呈现进度；任务有负责人与参与者（多人协作），通过 IM 做分发闭环；**没有禅道式的强里程碑/阶段对象**（无独立里程碑实体），节奏靠日历与任务截止时间。属于"轻节点、重协作"路线。

---

### 3. WookTeam（kuaifan/wookteam）—— DooTask 同作者的协作套件

**概览与活跃度**：轻量级在线团队协作工具（文档、思维导图、流程图、项目管理、任务分发、知识库），社区称"高颜值协作工具集"（[itpub 文章](https://z.itpub.net/article/detail/7339E567E73690A2B898912EEF59D2ED)），star 约 3K 量级；2025 年仍有实战教程（[CSDN 实战指南](https://blog.csdn.net/weixin_35006125/article/details/153040596)）。仓库：[kuaifan/wookteam](https://github.com/kuaifan/wookteam)（[fork：aalphaa/wookteam](https://github.com/aalphaa/wookteam)）。

**技术栈**：PHP（Laravel）+ Vue + MySQL。

**功能模型**：**项目 → 任务列表/线性看板**，并"提供时间维度的项目概览能力"（甘特/日历式总览，见上文 CSDN 指南）；任务分组、标签、优先级、负责人；知识库与文档协作融合。

**进度/节点/负责人**：与 DooTask 同类思路——任务看板 + 时间维概览替代显式里程碑；负责人落在任务级，无模块负责人机制。定位是"轻量协作+项目任务"，而非重型 PMO。

---

### 4. ActionView（lxerxa/actionview）—— 类 Jira 的问题/需求跟踪

**概览与活跃度**：面向中小企业的开源免费、类 Jira 的问题需求跟踪平台（[HelloGitHub 收录](https://hellogithub.com/repository/lxerxa/actionview)、[阿里云社区介绍](https://developer.aliyun.com/article/650946)），star 约 800+ 量级，近两年更新放缓，活跃度低-中。仓库：[lxerxa/actionview](https://github.com/lxerxa/actionview)。

**技术栈**：PHP（Laravel）+ React + MySQL。

**功能模型**：**项目 → 模块 → 问题(Issue)**；问题**类型可配置**（需求/任务/缺陷…）、**工作流（状态流转）可配置**、字段可配置，看板与列表视图、报告（燃尽图类）、权限角色管理。这是国内开源里最接近 Jira"可配置问题模型"的产品（[腾讯云类 Jira 介绍文](https://cloud.tencent.com.cn/developer/article/2238117)）。

**进度/节点/负责人**：进度靠问题状态流转与看板/报告呈现；有**模块**维度（问题挂模块）；指派通过问题负责人/报告人实现；无显式里程碑对象，迭代/版本概念弱于禅道。适合把"模块负责人"理解为"模块 × 问题指派"的场景参考。

---

### 5. Jitamin（jitamin/jitamin）—— 中文看板式（Kanboard 分支）

**概览与活跃度**：PHP 看板式项目管理，Kanboard 的中文社区分支（含 QQ 群运营，见[仓库](https://github.com/jitamin/jitamin)与[中文 README](https://raw.githubusercontent.com/jitamin/jitamin/refs/heads/master/README-zh_CN.md)），star 约 2K 量级；近年基本停更（源码站挂 v0.5.0，[52yma 页面](https://www.52yma.com/thread-206128-1-1.html)），活跃度低，但**设计思路（看板+时间跟踪）仍有参考价值**。

**技术栈**：PHP（Symfony 系，继承 Kanboard）+ SQLite/MySQL。

**功能模型**：**项目 → 看板 → 任务/子任务**，泳道、角色权限（项目管理员/成员/查看者）、任务时间跟踪（工时记录）、甘特图（插件）。**无里程碑/阶段/模块**概念，进度=看板列流转+工时统计，负责人=任务指派。代表"最轻量节点化"的极端。

---

### 6. PearProject（zikunh/pearProject）—— 补充样本（低星）

轻量级在线项目/任务协作系统，PHP 后端 + Vue2 前端一体化部署（[仓库](https://github.com/zikunh/pearProject)、[CSDN 部署文](https://blog.csdn.net/ppp33/article/details/161853065)）。模型为 项目→任务列表，含成员、任务指派与状态流转，无里程碑/阶段/模块负责人设计，star 约 1-2K 量级、活跃度低。收录它说明：**国产开源 PM 长尾里，"项目+任务+负责人"是基本盘，"里程碑/节点/模块负责人"是禅道及以上重型产品的分水岭**。

---

### 7. 国内商业产品公开资料（术语与交互模式）

**Teambition（钉钉）**：
- 模型：**项目集 → 项目 → 任务列表**（看板/列表/表格/日历/甘特图/资源多视图），子任务、任务分组；任务字段含**执行者、参与者**、截止时间、优先级、**工时**（开放平台有[按人维度汇总工时 API](https://open.teambition.com/docs/apis/63a4237d912d20d3b57fbbc4)）。
- 节点：**里程碑**明确存在，公开教程直接称"**项目里程碑节点**设置"（[php.cn 教程](https://www.php.cn/faq/2381270.html)）；钉钉集成版帮助中心亦有"如何开通项目里程碑（新版项目）"（[钉钉帮助](https://m.dingtalk.com/qidian/help-keyword-44375-9)），项目集介绍见[钉钉帮助中心](https://m.dingtalk.com/qidian/help-detail-1062200574)。
- 进度/汇报：甘特图进度条、**效能分析/报表**（[php.cn 效能分析](https://www.php.cn/faq/1848225.html)）、**汇报**（周报/日报）为招牌功能；任务新建与分配流程见[教程](https://www.php.cn/faq/2334027.html)。

**ONES**：
- 模型：**ONES Plan（项目集管理）→ ONES Project（项目/任务/迭代/里程碑）**，另有 Wiki/Testcase 套件（[ONES Plan 产品页](https://ones.cn/products/plan)）；瀑布解决方案主打"阶段+里程碑"规划与进度跟踪（[瀑布方案页](https://ones.cn/solutions/waterfall)）；甘特图支持关键字段标识优化（[官方博客](https://ones.cn/blog/product-guide/ones-project-gantt-chart)）；项目群管理文档见[ONES GitBook](https://guides-ones.gitbook.io/new/~gitbook/pdf?page=-M8KNlAQDFAkLoI0n4hd&only=yes)。
- 术语：项目集/项目群、迭代、里程碑、负责人、工时报表——与禅道术语高度同源。

**Worktile**：
- 模型：**项目集 → 项目 → 任务**（[项目集页](https://worktile.com/tools/project)）；2025 年 9.68 版发布"**阶段&交付系统**"：阶段（Stage）串联、阶段下挂**交付物/交付计划**，面向 IPD 场景（[官方博客](https://worktile.com/blog/v9-68-0-release)）；有独立"**进度管理页面**"（[帮助中心](https://worktile.com/kb/p/3872745)）。
- 术语：阶段、交付物、项目集、进度管理页、OKR。这是"阶段=流程节点+交付物产出"模式的代表。

**TAPD（腾讯）**：
- 模型：**产品 → 需求（需求池/Backlog）→ 迭代 → 任务/缺陷 → 发布计划（含里程碑）**，配故事墙、甘特图、测试计划、Wiki；官方文章《从需求池到发布上线的全链路管理》完整描述该链路（[TAPD 官方文章](https://www.tapd.cn/official/article_detail/1139987004001001128)）。
- 节点：**里程碑挂在发布计划下**，作为交付节点关联需求；甘特图用于排期与进度管理（[甘特图实战指南](https://www.tapd.cn/official/article_detail/1139987004001001138)）。
- 工时/进度：独立**工时统计**（按人/按需求，[腾讯云文档](https://cloud.tencent.cn/document/product/624/44330)）与**迭代统计/燃尽图**（[文档](https://cloud.tencent.cn/document/product/624/44324)），开放平台提供任务统计 API（[TAPD 开放平台](https://o.tapd.tencent.com/document/api-doc/API%e6%96%87%e6%a1%a3/api_reference/task/get_tasks_count.html)）。
- 术语：处理人、抄送、迭代、发布计划、里程碑、工时、燃尽图——"处理人+抄送"是国内大厂工具的通用叫法。

---

## 二、对比表

| 系统 | 类型/协议 | 技术栈 | 层级模型 | 进度节点 | 负责人机制 | 进度计算 | 工时 | 活跃度(约) |
|---|---|---|---|---|---|---|---|---|
| **禅道 ZenTao** | 开源(ZPL 许可)+商业版 | PHP 自研框架 + ZUI + MySQL | 项目集→项目→执行(迭代/阶段/看板)→需求/任务/Bug | 里程碑、阶段、迭代（三形态齐全） | 指派给 + 角色(PO/PM/QD/QA) + **模块默认指派人** | 任务完成比例 / 工时比例，燃尽图，项目集聚合 | 预估/消耗/剩余 | 高（v22.x 持续发版） |
| **DooTask** | 开源 | PHP(Laravel)+Vue+MySQL | 项目→任务列表(多视图) | 无独立里程碑，靠日历/截止时间 | 任务负责人+参与者，IM 分发 | 任务状态流+视图呈现 | 弱 | 中高（2025 仍更新、走 AI 路线） |
| **WookTeam** | 开源 | PHP(Laravel)+Vue+MySQL | 项目→任务(看板/甘特/日历概览) | 无里程碑，时间维度项目概览 | 任务负责人，团队成员 | 看板列+时间概览 | 弱 | 中 |
| **ActionView** | 开源免费 | PHP(Laravel)+React+MySQL | 项目→模块→问题(类型/工作流可配) | 弱（看板/报告，无里程碑） | 问题指派+报告人+角色 | 状态流转+燃尽图报告 | 弱 | 低-中 |
| **Jitamin** | 开源(Kanboard 分支) | PHP(Symfony)+SQLite/MySQL | 项目→看板→任务/子任务 | 无 | 任务指派+三级角色 | 看板列+时间跟踪 | 有(时间跟踪) | 低（近停更） |
| **PearProject** | 开源 | PHP+Vue2+MySQL | 项目→任务列表 | 无 | 成员+任务指派 | 状态流转 | 无 | 低 |
| **Teambition** | 商业 SaaS(钉钉) | — | 项目集→项目→任务(多视图) | **里程碑节点**、甘特图 | 执行者+参与者+工时 | 甘特进度+效能分析报表 | 有(API) | 活跃 |
| **ONES** | 商业 SaaS | — | 项目集(Plan)→项目→迭代/里程碑→任务 | 里程碑、瀑布阶段 | 负责人+成员 | 甘特图+工时报表 | 有 | 活跃 |
| **Worktile** | 商业 SaaS | — | 项目集→项目→任务 | **阶段&交付系统**(阶段+交付物) | 任务负责人+成员 | 进度管理页+甘特 | 有 | 活跃 |
| **TAPD** | 商业 SaaS(腾讯) | — | 产品→需求→迭代→任务/缺陷→发布计划 | 发布计划下**里程碑**、迭代 | 处理人+抄送 | 甘特图+迭代统计+燃尽图 | 工时统计(按人/按需求) | 活跃 |

---

## 三、总结

### 3.1 「进度节点化 + 模块负责人」在国内项目管理语境下的常见设计模式

1. **层级骨架共识**：`项目集(Program/项目群) → 项目 → 执行单元(迭代/阶段) → 工作项(需求/任务/Bug)`。项目集负责多项目聚合，禅道/ONES/Worktile/Teambition 全部引入（禅道[论坛链](https://www.zentao.net/thread/296515.html)、[ONES Plan](https://ones.cn/products/plan)、[Worktile 项目集](https://worktile.com/tools/project)）。

2. **"节点"的三种形态**（国内产品混用，但语义可区分）：
   - **里程碑（时间节点）**：有名称、计划/实际日期，锚定交付时刻，可关联工作项——禅道瀑布、Teambition"里程碑节点"、TAPD 发布计划；
   - **阶段（流程节点）**：按生命周期分段（需求→设计→开发→测试→发布），阶段下挂任务、顺序推进——禅道瀑布、Worktile"阶段&交付系统"（阶段+交付物）、ONES 瀑布；
   - **迭代/冲刺（节奏节点）**：固定时间盒，配燃尽图监控——禅道敏捷、TAPD。
   共同点：**节点 = 名称 + 时间 + 状态 + 可挂工作项 + （可设负责人）→ 进度向下聚合**。

3. **模块负责人模式**：国内"模块"普遍是**分类树**（产品功能/需求模块），负责人机制有两种落地：① 禅道式——模块维护里设**默认指派人**，新需求/Bug 按模块自动带出（[ask/2971](https://www.zentao.net/ask/2971.html)）；② ActionView 式——问题挂模块，负责人落在问题指派。即"模块负责人"更多是**默认责任映射规则**，而非独立对象。

4. **负责人双轨制**：**责任主体（负责人/责任人/Owner）** 与 **执行主体（指派人/处理人/执行者/Assignee）** 分离，外加**抄送/参与者**围观。禅道（指派给+角色）、Teambition（执行者+参与者）、TAPD（处理人+抄送）一致。

5. **进度计算双口径**：**完成比例**（完成数/总数）与**工时比例**（消耗/(消耗+剩余)，燃尽图描点）并存，上层（项目/项目集）进度=下层聚合加权（[禅道项目集进度问答](https://www.zentao.net/ask/599039.html)）。任务同时维护"预估/消耗/剩余"三值工时是国产工具的标配（禅道、TAPD 工时统计、Teambition 工时 API）。

6. **延期与汇报的表达**：延期=实际日期晚于计划日期的偏差，用里程碑状态/红黄预警呈现；汇报=周报/项目报告对象化（禅道[项目报告](https://www.zentao.net/book/zentaopms/1813.html)、Teambition 汇报、TAPD 报表），形成"节点→偏差→汇报"闭环。

### 3.2 术语表（国内项目管理语境）

| 术语 | 含义 | 英文/变体 |
|---|---|---|
| 里程碑/节点 | 阶段性目标的时间锚点，含计划/实际日期 | Milestone |
| 阶段 | 项目生命周期的流程分段，段内挂任务 | Stage/Phase |
| 迭代/冲刺 | 固定时间盒的开发周期 | Iteration/Sprint |
| 项目集/项目群 | 多项目的组合管理容器 | Program/Portfolio |
| 模块 | 产品功能/需求的分类树，工作项挂模块 | Module |
| 负责人/责任人 | 对结果负责的人 | Owner/Responsible |
| 指派人/处理人/执行者 | 具体干活的人 | Assignee/Executor |
| 抄送/参与者 | 知会/围观者 | CC/Participant |
| 工时 | 预估/消耗/剩余三值 | Estimated/Spent/Left |
| 进度 | 完成百分比（完成比例或工时比例） | Progress |
| 燃尽图/燃起图 | 剩余工时随时间的下降/上升曲线 | Burndown/Burnup |
| 延期 | 实际晚于计划（节点/任务级） | Delay/Overdue |
| 交付物 | 阶段/节点的产出物 | Deliverable |
| 汇报 | 周报/日报/项目报告 | Report |
| 发布计划/发布 | 版本发布节点（可挂里程碑） | Release |

### 3.3 可借鉴点（对一个"项目时间线 + 进度节点 + 模块负责人"产品的建议）

1. **核心对象链**：`项目 → 进度节点(里程碑/阶段) → 任务`；节点字段建议：名称、计划/实际起止日期、状态（未开始/进行中/已完成/**延期**）、节点负责人、所属阶段类型、关联模块。
2. **模块负责人做成映射规则而非独立对象**：模块树 + 模块默认负责人 → 新任务按模块自动带出指派人（禅道 ask/2971 已验证的成熟交互）；任务上再允许改派（负责人≠执行人的双轨）。
3. **进度聚合**：节点进度 = 其下任务按工时（缺工时则按数量）加权；项目进度 = 节点加权或工时比例；提供剩余工时燃尽图，参考禅道[手册 105](https://www.zentao.net/book/zentaopms/105.html)。
4. **延期可视化**：计划 vs 实际日期偏差自动标红/预警，延期节点在时间线上突出；配周报式"汇报"输出（禅道项目报告、Teambition 汇报模式）。
5. **术语对齐国内习惯**：用「里程碑/节点、阶段、模块、负责人、指派人、抄送、工时（预估/消耗/剩余）、延期、汇报」，避免自造词；对比表显示国内开源 PM 技术栈主流是 **PHP(Laravel)+Vue**（DooTask/WookTeam/ActionView/PearProject），全栈 TS 或 PHP+React 亦可，参考行动是产品模型而非技术选型。

---

### 附：主要参考来源
- 禅道官方手册：50/105/487/517/654/1700/1813 各篇（zentao.net/book/zentaopms/）；官方 FAQ（zentao.cn/faqvideo/execute-iterate-stage-204）；官方问答 ask/2971、ask/599039、ask/597568
- 仓库与帮助中心：github.com/easysoft/zentaopms、kuaifan/dootask（dootask.com/help）、kuaifan/wookteam、lxerxa/actionview、jitamin/jitamin、zikunh/pearProject
- 商业产品公开资料：php.cn Teambition 教程、open.teambition.com API 文档、钉钉帮助中心、ones.cn（Plan/瀑布/甘特图）、worktile.com（9.68 阶段&交付、项目集、进度管理页）、tapd.cn 官方文章与 cloud.tencent.cn TAPD 文档（44330/44324/44285）、o.tapd.tencent.com 开放平台
