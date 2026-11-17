-- Grant return actions and data scopes explicitly; no automatic role privileges.
START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50021,'退货处理',50000,16,'returns','warehouse/returns/index','','WarehouseReturns',1,1,'C','0','0','warehouse:returns:list','retweet','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50230,'退货列表',50021,1,'','','','',1,1,'F','0','0','warehouse:returns:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50231,'退货详情',50021,2,'','','','',1,1,'F','0','0','warehouse:returns:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50232,'登记退货',50021,3,'','','','',1,1,'F','0','0','warehouse:returns:add','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50233,'隔离签收',50021,4,'','','','',1,1,'F','0','0','warehouse:returns:receive','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50234,'退货检验',50021,5,'','','','',1,1,'F','0','0','warehouse:returns:inspect','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50235,'退货上架',50021,6,'','','','',1,1,'F','0','0','warehouse:returns:restock','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50236,'退货报废',50021,7,'','','','',1,1,'F','0','0','warehouse:returns:scrap','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50237,'退货结案',50021,8,'','','','',1,1,'F','0','0','warehouse:returns:close','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50238,'取消退货',50021,9,'','','','',1,1,'F','0','0','warehouse:returns:cancel','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'');
COMMIT;
