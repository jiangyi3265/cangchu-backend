-- Separate action permissions; grants to existing roles remain explicit.
START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50210,'逐件扫码',50016,11,'','','','',1,1,'F','0','0','warehouse:orders:scan','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50211,'撤销扫码',50016,12,'','','','',1,1,'F','0','0','warehouse:orders:unscan','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50212,'确认装箱',50016,13,'','','','',1,1,'F','0','0','warehouse:orders:pack','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50213,'撤销装箱',50016,14,'','','','',1,1,'F','0','0','warehouse:orders:unpack','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,''),
(50214,'返回拣货核验',50016,15,'','','','',1,1,'F','0','0','warehouse:orders:reopenpick','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'');
COMMIT;
