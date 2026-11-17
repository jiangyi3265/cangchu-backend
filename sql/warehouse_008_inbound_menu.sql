-- No role permissions or customer/warehouse scopes are granted automatically.
START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50019,'入库管理',50000,14,'inbound','warehouse/inbound/index','','WarehouseInbound',1,1,'C','0','0','warehouse:asns:list','download','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Native partial receiving and direct putaway.'),
(50200,'入库查询',50019,1,'','','','',1,1,'F','0','0','warehouse:asns:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50201,'入库详情',50019,2,'','','','',1,1,'F','0','0','warehouse:asns:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50202,'入库新增',50019,3,'','','','',1,1,'F','0','0','warehouse:asns:add','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50203,'入库修改',50019,4,'','','','',1,1,'F','0','0','warehouse:asns:edit','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50204,'收货上架',50019,5,'','','','',1,1,'F','0','0','warehouse:asns:receive','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50205,'入库结案',50019,6,'','','','',1,1,'F','0','0','warehouse:asns:close','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50206,'取消入库',50019,7,'','','','',1,1,'F','0','0','warehouse:asns:cancel','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'');
COMMIT;
