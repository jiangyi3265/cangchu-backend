-- Roles and client/warehouse scopes must be granted explicitly.
START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50020,'库存盘点',50000,15,'stocktakes','warehouse/stocktakes/index','','WarehouseStocktakes',1,1,'C','0','0','warehouse:stocktakes:list','list','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50220,'盘点列表',50020,1,'','','','',1,1,'F','0','0','warehouse:stocktakes:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50221,'盘点详情',50020,2,'','','','',1,1,'F','0','0','warehouse:stocktakes:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50222,'开始盘点',50020,3,'','','','',1,1,'F','0','0','warehouse:stocktakes:add','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50223,'录入实盘',50020,4,'','','','',1,1,'F','0','0','warehouse:stocktakes:count','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50224,'补录盘盈',50020,5,'','','','',1,1,'F','0','0','warehouse:stocktakes:found','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50225,'提交复核',50020,6,'','','','',1,1,'F','0','0','warehouse:stocktakes:submit','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50226,'退回重盘',50020,7,'','','','',1,1,'F','0','0','warehouse:stocktakes:reopen','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50227,'审核入账',50020,8,'','','','',1,1,'F','0','0','warehouse:stocktakes:approve','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50228,'取消盘点',50020,9,'','','','',1,1,'F','0','0','warehouse:stocktakes:cancel','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'');
COMMIT;
