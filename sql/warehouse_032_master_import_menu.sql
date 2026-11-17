-- Import and export permissions are independent; existing write permissions still apply.
START TRANSACTION;
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50256,'客户批量导入',50010,10,'','','','',1,1,'F','0','0','warehouse:clients:import','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50257,'客户导出',50010,11,'','','','',1,1,'F','0','0','warehouse:clients:export','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50258,'仓库批量导入',50011,10,'','','','',1,1,'F','0','0','warehouse:warehouses:import','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50259,'仓库导出',50011,11,'','','','',1,1,'F','0','0','warehouse:warehouses:export','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50260,'库位类型批量导入',50012,10,'','','','',1,1,'F','0','0','warehouse:locationTypes:import','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50261,'库位类型导出',50012,11,'','','','',1,1,'F','0','0','warehouse:locationTypes:export','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50262,'库位批量导入',50013,10,'','','','',1,1,'F','0','0','warehouse:locations:import','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50263,'库位导出',50013,11,'','','','',1,1,'F','0','0','warehouse:locations:export','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50264,'商品批量导入',50014,10,'','','','',1,1,'F','0','0','warehouse:products:import','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50265,'商品导出',50014,11,'','','','',1,1,'F','0','0','warehouse:products:export','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants');
COMMIT;
