-- ============================================================
-- 2026-11-12  为订单兜底扫描补充组合索引
-- ============================================================
-- 背景：
--   OrderCancelReconcileTask 每 5 分钟执行一次扫描：
--     SELECT id FROM oms_order
--      WHERE status = 0 AND delete_status = 0
--        AND create_time < NOW() - 35 分钟
--        AND create_time > NOW() - 7 天
--      ORDER BY create_time ASC LIMIT 100
--
--   原 oms_order 只有主键索引，该查询会「全表扫描 + filesort」，
--   耗时随订单总量线性增长，大促期间会明显加重数据库负担。
--
-- 索引设计：
--   1. status、delete_status 是等值条件，放最前面；
--   2. create_time 是范围条件且用于排序，放最后；
--   3. 这样「等值 + 范围 + 排序」可以全部走索引，无需额外排序。
--   结果：扫描行数只与实际「超时未支付」的订单数相关，与总订单量无关。
-- ============================================================

ALTER TABLE oms_order
    ADD INDEX idx_status_delete_create (status, delete_status, create_time);

-- 验证执行计划（type 应为 range，Extra 不应出现 Using filesort）：
-- EXPLAIN SELECT id FROM oms_order
--  WHERE status = 0 AND delete_status = 0
--    AND create_time < DATE_SUB(NOW(), INTERVAL 35 MINUTE)
--    AND create_time > DATE_SUB(NOW(), INTERVAL 7 DAY)
--  ORDER BY create_time ASC LIMIT 100;
