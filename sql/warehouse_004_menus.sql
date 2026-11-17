-- Apply once after warehouse_003_orders.sql. Permission grants are explicit per role.
-- Does not grant any customer/warehouse scope or any shipping authority automatically.
START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50015,'库存作业',50000,15,'inventory','warehouse/inventory/index','','WarehouseInventory',1,1,'C','0','0','warehouse:inventory:list','table','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50016,'订单履约',50000,16,'orders','warehouse/orders/index','','WarehouseOrders',1,1,'C','0','0','warehouse:orders:list','shopping','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50017,'数据范围',50000,17,'scopes','warehouse/scopes/index','','WarehouseScopes',1,1,'C','0','0','warehouse:scope:query','lock','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50150,'库存 list',50015,1,'','','','',1,1,'F','0','0','warehouse:inventory:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50151,'库存 move',50015,2,'','','','',1,1,'F','0','0','warehouse:inventory:move','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50160,'订单 list',50016,1,'','','','',1,1,'F','0','0','warehouse:orders:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50161,'订单 query',50016,2,'','','','',1,1,'F','0','0','warehouse:orders:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50162,'订单 add',50016,3,'','','','',1,1,'F','0','0','warehouse:orders:add','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50163,'订单 edit',50016,4,'','','','',1,1,'F','0','0','warehouse:orders:edit','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50164,'订单 allocate',50016,5,'','','','',1,1,'F','0','0','warehouse:orders:allocate','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50165,'订单 pick',50016,6,'','','','',1,1,'F','0','0','warehouse:orders:pick','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50166,'订单 cancel',50016,7,'','','','',1,1,'F','0','0','warehouse:orders:cancel','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50167,'订单 despatch',50016,8,'','','','',1,1,'F','0','0','warehouse:orders:despatch','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50168,'订单 hold',50016,9,'','','','',1,1,'F','0','0','warehouse:orders:hold','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50169,'订单 release',50016,10,'','','','',1,1,'F','0','0','warehouse:orders:release','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50190,'财务 query',50001,1,'','','','',1,1,'F','0','0','warehouse:finance:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50191,'财务 edit',50001,2,'','','','',1,1,'F','0','0','warehouse:finance:edit','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50192,'数据范围 query',50017,1,'','','','',1,1,'F','0','0','warehouse:scope:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50193,'数据范围 edit',50017,2,'','','','',1,1,'F','0','0','warehouse:scope:edit','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'');
COMMIT;
