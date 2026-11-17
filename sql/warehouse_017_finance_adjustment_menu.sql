-- No automatic role grants. Configure approval and execution separately.
START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50310,'财务调整批准',50018,3,'','','','',1,1,'F','0','0','warehouse:finance:approveAdjustment','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Approve or revoke a reviewed financial adjustment.'),
(50311,'财务调整执行',50018,4,'','','','',1,1,'F','0','0','warehouse:finance:executeAdjustment','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Execute an approved adjustment; policy may require a different actor.');
COMMIT;
