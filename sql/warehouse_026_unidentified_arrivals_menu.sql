START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50249,'登记未识别到货',50019,17,'','','','',1,1,'F','0','0','warehouse:asns:unknownArrive','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50250,'批准认领或处置',50019,18,'','','','',1,1,'F','0','0','warehouse:asns:unknownApprove','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50251,'执行认领或交接',50019,19,'','','','',1,1,'F','0','0','warehouse:asns:unknownExecute','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50252,'撤销未执行认领',50019,20,'','','','',1,1,'F','0','0','warehouse:asns:unknownCancel','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'');
COMMIT;
