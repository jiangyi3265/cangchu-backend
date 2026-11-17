START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50253,'批准收货更正',50019,21,'','','','',1,1,'F','0','0','warehouse:asns:approveCorrection','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50254,'执行收货更正',50019,22,'','','','',1,1,'F','0','0','warehouse:asns:executeCorrection','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50255,'撤销未执行更正',50019,23,'','','','',1,1,'F','0','0','warehouse:asns:cancelCorrection','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'');
COMMIT;
