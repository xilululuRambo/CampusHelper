package com.rambo.module.notification.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rambo.module.notification.pojo.entity.Notification;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
