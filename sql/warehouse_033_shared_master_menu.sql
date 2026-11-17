-- Shared suppliers and sales channels. No existing role grants or business records are changed.
START TRANSACTION;
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50022,'供应商管理',50000,22,'master/suppliers','warehouse/master-data/index','{"kind":"suppliers"}','WarehouseSuppliers',1,1,'C','0','0','warehouse:suppliers:list','list','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Shared catalog; administrators maintain records'),
(50266,'供应商管理列表',50022,1,'','','','',1,1,'F','0','0','warehouse:suppliers:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50267,'供应商管理详情',50022,2,'','','','',1,1,'F','0','0','warehouse:suppliers:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50268,'供应商管理新增',50022,3,'','','','',1,1,'F','0','0','warehouse:suppliers:add','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50269,'供应商管理修改',50022,4,'','','','',1,1,'F','0','0','warehouse:suppliers:edit','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50270,'供应商管理删除',50022,5,'','','','',1,1,'F','0','0','warehouse:suppliers:remove','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50271,'供应商管理导入',50022,6,'','','','',1,1,'F','0','0','warehouse:suppliers:import','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50272,'供应商管理导出',50022,7,'','','','',1,1,'F','0','0','warehouse:suppliers:export','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50023,'销售渠道',50000,23,'master/channels','warehouse/master-data/index','{"kind":"channels"}','WarehouseChannels',1,1,'C','0','0','warehouse:channels:list','list','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Shared catalog; administrators maintain records'),
(50273,'销售渠道列表',50023,1,'','','','',1,1,'F','0','0','warehouse:channels:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50274,'销售渠道详情',50023,2,'','','','',1,1,'F','0','0','warehouse:channels:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50275,'销售渠道新增',50023,3,'','','','',1,1,'F','0','0','warehouse:channels:add','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50276,'销售渠道修改',50023,4,'','','','',1,1,'F','0','0','warehouse:channels:edit','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50277,'销售渠道删除',50023,5,'','','','',1,1,'F','0','0','warehouse:channels:remove','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50278,'销售渠道导入',50023,6,'','','','',1,1,'F','0','0','warehouse:channels:import','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants'),
(50279,'销售渠道导出',50023,7,'','','','',1,1,'F','0','0','warehouse:channels:export','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'No automatic role grants');
COMMIT;
