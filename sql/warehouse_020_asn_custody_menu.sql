-- Separate operational rights; existing roles receive no implicit new grants.
START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50240,'入库隔离签收',50019,8,'','','','',1,1,'F','0','0','warehouse:asns:stage','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50241,'入库检验',50019,9,'','','','',1,1,'F','0','0','warehouse:asns:inspect','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50242,'超收批准',50019,10,'','','','',1,1,'F','0','0','warehouse:asns:approveOverage','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50243,'执行上架',50019,11,'','','','',1,1,'F','0','0','warehouse:asns:putaway','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50244,'入库报废',50019,12,'','','','',1,1,'F','0','0','warehouse:asns:scrap','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'');
COMMIT;
