-- Explicit role grants required. Configuring connections is additionally super-admin only.
START TRANSACTION;
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50024,'店铺对接',50000,24,'integrations','warehouse/integrations/index','','WarehouseIntegrations',1,1,'C','0','0','warehouse:integrations:list','link','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'WooCommerce order intake; no warehouse order import or remote writes'),
(50280,'店铺对接列表',50024,1,'','','','',1,1,'F','0','0','warehouse:integrations:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50281,'店铺对接详情',50024,2,'','','','',1,1,'F','0','0','warehouse:integrations:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50282,'店铺连接配置',50024,3,'','','','',1,1,'F','0','0','warehouse:integrations:configure','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Additionally restricted to the super administrator'),
(50283,'店铺订单拉取',50024,4,'','','','',1,1,'F','0','0','warehouse:integrations:pull','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Explicit bounded read; no automatic timer');
COMMIT;
