-- Warehouse menus for the RuoYi schema, applied once after warehouse_001.sql.
-- Reserved menu IDs: 50000-50999. No role or data-scope grants are implied.
-- A duplicate ID fails this migration instead of overwriting existing permissions.
-- Titles are translated by the frontend route-title dictionary; no sys_menu column is added.
START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50000,'仓储管理',0,0,'warehouse','','','Warehouse',1,1,'M','0','0','','warehouse','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Native Java warehouse module; no legacy Node forwarding.'),
(50001,'仓储首页',50000,1,'dashboard','warehouse/dashboard/index','','WarehouseDashboard',1,1,'C','0','0','warehouse:dashboard:query','dashboard','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50010,'客户管理',50000,10,'master/clients','warehouse/master-data/index','{"kind":"clients"}','WarehouseClients',1,1,'C','0','0','warehouse:clients:list','list','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50100,'客户管理列表',50010,1,'','','','',1,1,'F','0','0','warehouse:clients:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50101,'客户管理详情',50010,2,'','','','',1,1,'F','0','0','warehouse:clients:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50102,'客户管理新增',50010,3,'','','','',1,1,'F','0','0','warehouse:clients:add','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50103,'客户管理修改',50010,4,'','','','',1,1,'F','0','0','warehouse:clients:edit','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50104,'客户管理删除',50010,5,'','','','',1,1,'F','0','0','warehouse:clients:remove','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50011,'仓库管理',50000,11,'master/warehouses','warehouse/master-data/index','{"kind":"warehouses"}','WarehouseWarehouses',1,1,'C','0','0','warehouse:warehouses:list','list','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50110,'仓库管理列表',50011,1,'','','','',1,1,'F','0','0','warehouse:warehouses:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50111,'仓库管理详情',50011,2,'','','','',1,1,'F','0','0','warehouse:warehouses:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50112,'仓库管理新增',50011,3,'','','','',1,1,'F','0','0','warehouse:warehouses:add','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50113,'仓库管理修改',50011,4,'','','','',1,1,'F','0','0','warehouse:warehouses:edit','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50114,'仓库管理删除',50011,5,'','','','',1,1,'F','0','0','warehouse:warehouses:remove','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50012,'库位类型',50000,12,'master/locationTypes','warehouse/master-data/index','{"kind":"locationTypes"}','WarehouseLocationTypes',1,1,'C','0','0','warehouse:locationTypes:list','list','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50120,'库位类型列表',50012,1,'','','','',1,1,'F','0','0','warehouse:locationTypes:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50121,'库位类型详情',50012,2,'','','','',1,1,'F','0','0','warehouse:locationTypes:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50122,'库位类型新增',50012,3,'','','','',1,1,'F','0','0','warehouse:locationTypes:add','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50123,'库位类型修改',50012,4,'','','','',1,1,'F','0','0','warehouse:locationTypes:edit','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50124,'库位类型删除',50012,5,'','','','',1,1,'F','0','0','warehouse:locationTypes:remove','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50013,'库位管理',50000,13,'master/locations','warehouse/master-data/index','{"kind":"locations"}','WarehouseLocations',1,1,'C','0','0','warehouse:locations:list','list','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50130,'库位管理列表',50013,1,'','','','',1,1,'F','0','0','warehouse:locations:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50131,'库位管理详情',50013,2,'','','','',1,1,'F','0','0','warehouse:locations:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50132,'库位管理新增',50013,3,'','','','',1,1,'F','0','0','warehouse:locations:add','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50133,'库位管理修改',50013,4,'','','','',1,1,'F','0','0','warehouse:locations:edit','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50134,'库位管理删除',50013,5,'','','','',1,1,'F','0','0','warehouse:locations:remove','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50014,'商品管理',50000,14,'master/products','warehouse/master-data/index','{"kind":"products"}','WarehouseProducts',1,1,'C','0','0','warehouse:products:list','list','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50140,'商品管理列表',50014,1,'','','','',1,1,'F','0','0','warehouse:products:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50141,'商品管理详情',50014,2,'','','','',1,1,'F','0','0','warehouse:products:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50142,'商品管理新增',50014,3,'','','','',1,1,'F','0','0','warehouse:products:add','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50143,'商品管理修改',50014,4,'','','','',1,1,'F','0','0','warehouse:products:edit','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50144,'商品管理删除',50014,5,'','','','',1,1,'F','0','0','warehouse:products:remove','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50090,'待迁移说明',50000,90,'pending','warehouse/pending/index','','WarehousePending',1,1,'C','0','0','warehouse:catalog:list','documentation','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Read-only migration status, not a claim that legacy workflows are complete.'),
(50900,'仓储字段目录',50000,100,'','','','',1,1,'F','0','0','warehouse:catalog:list','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50901,'仓储首页查询',50001,1,'','','','',1,1,'F','0','0','warehouse:dashboard:query','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'');
COMMIT;
