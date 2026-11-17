START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50245,'批准退供',50019,13,'','','','',1,1,'F','0','0','warehouse:asns:approveReturn','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50246,'退供实物交接',50019,14,'','','','',1,1,'F','0','0','warehouse:asns:returnSupplier','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50247,'撤销退供批准',50019,15,'','','','',1,1,'F','0','0','warehouse:asns:cancelReturnApproval','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'');
COMMIT;
