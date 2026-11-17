START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50248,'批准迟到补单',50019,16,'','','','',1,1,'F','0','0','warehouse:asns:lateSupplement','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'明确补单管理费与 SKU 计费策略');
COMMIT;
