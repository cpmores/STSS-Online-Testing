package com.stss.online_testing.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import java.util.Date;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        Date now = new Date();

        // 统一补齐常见审计字段，避免依赖数据库默认值时出现空时间。
        strictInsertFill(metaObject, "createTime", Date.class, now);
        strictInsertFill(metaObject, "updateTime", Date.class, now);
        strictInsertFill(metaObject, "isDeleted", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updateTime", Date.class, new Date());
    }
}
