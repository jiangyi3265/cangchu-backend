-- No automatic grants. Approval is separate from financial editing / posting.
START TRANSACTION;
INSERT INTO sys_menu (menu_id,menu_name,parent_id,order_num,path,component,`query`,route_name,is_frame,is_cache,menu_type,visible,status,perms,icon,create_by,create_time,update_by,update_time,remark) VALUES
(50312,'历史仓储认领复核批准',50018,5,'','','','',1,1,'F','0','0','warehouse:finance:approveStorageReview','#','warehouse-migration',CURRENT_TIMESTAMP,'',NULL,'Approve or revoke historical identity allocations; a different actor must prepare and post.');
COMMIT;
