START TRANSACTION;
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50284,'店铺来源授权',50024,5,'','','','',1,1,'F','0','0','warehouse:integrations:authority','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Additionally super-admin only; explicit cutover responsibility'),
(50285,'店铺商品对照',50024,6,'','','','',1,1,'F','0','0','warehouse:integrations:mapping','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Additionally super-admin only; source SKU and product identity'),
(50286,'店铺订单核验',50024,7,'','','','',1,1,'F','0','0','warehouse:integrations:review','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Read paid order, physical products and existing fulfillments'),
(50287,'店铺订单接单',50024,8,'','','','',1,1,'F','0','0','warehouse:integrations:accept','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Create one source-linked warehouse order; no remote writes');
COMMIT;
