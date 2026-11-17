-- Apply after warehouse_005_finance.sql; creates no role or data-scope grants.
START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50018,'费用与账单',50000,18,'finance','warehouse/finance/index','','WarehouseFinance',1,1,'C','0','0','warehouse:finance:query','money','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Native finance rules, charges, billing runs and invoices.');
UPDATE sys_menu SET parent_id=50018,menu_name='财务查询',order_num=1 WHERE menu_id=50190 AND perms='warehouse:finance:query';
UPDATE sys_menu SET parent_id=50018,menu_name='财务修改',order_num=2 WHERE menu_id=50191 AND perms='warehouse:finance:edit';
COMMIT;
