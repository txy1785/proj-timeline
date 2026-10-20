package com.projtimeline.file;

import java.util.List;

/**
 * 文档分类字典。项目级分类覆盖软件工程文档主线（GB/T 8567-2006 的可行性/计划/需求/设计/测试/运维/验收 + 管理过程），
 * 模块级分类对应负责人上传的开发过程痕迹。
 * <p>
 * 新增或调整分类只改这里，表结构与接口不变；历史数据按 code 保留。
 */
public final class DocumentCatalog {
    private DocumentCatalog() {}

    public static final String OWNER_PROJECT = "PROJECT";
    public static final String OWNER_MODULE = "MODULE";
    public static final String OWNER_NODE = "NODE";

    public record Category(String ownerType, String group, String code, String name) {}

    private static final List<Category> CATEGORIES = List.of(
            // 项目级 · 立项与计划
            new Category(OWNER_PROJECT, "立项与计划", "PROJECT_CHARTER", "立项与项目章程"),
            new Category(OWNER_PROJECT, "立项与计划", "PROJECT_PLAN", "项目计划"),
            new Category(OWNER_PROJECT, "立项与计划", "RESOURCE_PLAN", "资源与进度安排"),
            // 项目级 · 需求
            new Category(OWNER_PROJECT, "需求", "REQUIREMENTS", "需求规格说明"),
            new Category(OWNER_PROJECT, "需求", "REQUIREMENT_CHANGE", "需求变更记录"),
            new Category(OWNER_PROJECT, "需求", "PROTOTYPE", "原型与交互稿"),
            // 项目级 · 设计规范
            new Category(OWNER_PROJECT, "设计规范", "HIGH_LEVEL_DESIGN", "概要设计"),
            new Category(OWNER_PROJECT, "设计规范", "DETAILED_DESIGN", "详细设计"),
            new Category(OWNER_PROJECT, "设计规范", "API_DESIGN", "接口设计"),
            new Category(OWNER_PROJECT, "设计规范", "DB_DESIGN", "数据库设计"),
            new Category(OWNER_PROJECT, "设计规范", "CODING_STANDARD", "技术规范与编码规范"),
            new Category(OWNER_PROJECT, "设计规范", "ARCHITECTURE", "架构与技术选型"),
            // 项目级 · 测试质量
            new Category(OWNER_PROJECT, "测试质量", "TEST_PLAN", "测试计划"),
            new Category(OWNER_PROJECT, "测试质量", "TEST_CASE", "测试用例"),
            new Category(OWNER_PROJECT, "测试质量", "TEST_REPORT", "测试报告"),
            new Category(OWNER_PROJECT, "测试质量", "DEFECT_LOG", "缺陷与问题记录"),
            // 项目级 · 发布运维
            new Category(OWNER_PROJECT, "发布运维", "DEPLOY_MANUAL", "部署手册"),
            new Category(OWNER_PROJECT, "发布运维", "OPS_MANUAL", "运维手册"),
            new Category(OWNER_PROJECT, "发布运维", "RELEASE_NOTE", "版本说明与发布记录"),
            new Category(OWNER_PROJECT, "发布运维", "USER_MANUAL", "用户操作手册"),
            // 项目级 · 验收收尾
            new Category(OWNER_PROJECT, "验收收尾", "ACCEPTANCE", "验收报告"),
            new Category(OWNER_PROJECT, "验收收尾", "PROJECT_SUMMARY", "项目总结"),
            new Category(OWNER_PROJECT, "验收收尾", "RETROSPECTIVE", "复盘与经验沉淀"),
            // 项目级 · 管理过程
            new Category(OWNER_PROJECT, "管理过程", "MEETING_NOTE", "会议纪要"),
            new Category(OWNER_PROJECT, "管理过程", "PERIODIC_REPORT", "周报与月报"),
            new Category(OWNER_PROJECT, "管理过程", "RISK_LOG", "风险登记"),
            new Category(OWNER_PROJECT, "管理过程", "EXTERNAL_MATERIAL", "对外材料与合同"),
            new Category(OWNER_PROJECT, "其他", "PROJECT_OTHER", "其他项目文件"),
            // 模块级 · 模块开发
            new Category(OWNER_MODULE, "模块开发", "MODULE_DESIGN", "模块设计文档"),
            new Category(OWNER_MODULE, "模块开发", "MODULE_FLOW", "流程图与时序图"),
            new Category(OWNER_MODULE, "模块开发", "MODULE_API", "模块接口说明"),
            new Category(OWNER_MODULE, "模块开发", "DEV_LOG", "开发记录"),
            new Category(OWNER_MODULE, "模块开发", "DESIGN_REVIEW", "设计评审记录"),
            new Category(OWNER_MODULE, "模块开发", "JOINT_TEST", "联调与自测记录"),
            new Category(OWNER_MODULE, "模块开发", "DELIVERABLE", "交付物与截图"),
            new Category(OWNER_MODULE, "其他", "MODULE_OTHER", "其他模块文件"),
            // 节点级 · 功能节点产出
            new Category(OWNER_NODE, "节点产出", "NODE_DESIGN", "节点设计说明"),
            new Category(OWNER_NODE, "节点产出", "NODE_FLOW", "流程图与时序图"),
            new Category(OWNER_NODE, "节点产出", "NODE_API", "接口与数据说明"),
            new Category(OWNER_NODE, "节点产出", "NODE_TEST", "自测与联调记录"),
            new Category(OWNER_NODE, "节点产出", "NODE_SCREENSHOT", "截图与演示"),
            new Category(OWNER_NODE, "节点产出", "NODE_DELIVERABLE", "节点交付物"),
            new Category(OWNER_NODE, "其他", "NODE_OTHER", "其他节点文件")
    );

    public static List<Category> all() { return CATEGORIES; }

    public static Category find(String ownerType, String code) {
        return CATEGORIES.stream()
                .filter(item -> item.ownerType().equals(ownerType) && item.code().equals(code))
                .findFirst().orElse(null);
    }

    public static boolean supports(String ownerType) {
        return OWNER_PROJECT.equals(ownerType) || OWNER_MODULE.equals(ownerType) || OWNER_NODE.equals(ownerType);
    }
}
