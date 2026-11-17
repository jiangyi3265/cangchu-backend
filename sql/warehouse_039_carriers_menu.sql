-- No automatic role grants. Connection changes also require built-in super administrator.
START TRANSACTION;
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50025,'快递发运',50000,25,'carriers','warehouse/carriers/index','','WarehouseCarriers',1,1,'C','0','0','warehouse:carriers:list','delivery','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'DPD UK / DPD Local UK shipment creation and original labels'),
(50288,'快递连接列表',50025,1,'','','','',1,1,'F','0','0','warehouse:carriers:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50289,'快递记录详情',50025,2,'','','','',1,1,'F','0','0','warehouse:carriers:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50290,'快递连接配置',50025,3,'','','','',1,1,'F','0','0','warehouse:carriers:configure','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Super administrator only'),
(50291,'快递服务查询',50025,4,'','','','',1,1,'F','0','0','warehouse:carriers:services','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Requires source order access'),
(50292,'申请快递面单',50025,5,'','','','',1,1,'F','0','0','warehouse:carriers:book','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Creates carrier shipment; not physical warehouse handover'),
(50293,'恢复快递请求',50025,6,'','','','',1,1,'F','0','0','warehouse:carriers:recover','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Never resends a shipment with unknown creation outcome'),
(50294,'下载快递面单',50025,7,'','','','',1,1,'F','0','0','warehouse:carriers:label','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Original ZPL download only; no automatic printing');
COMMIT;
