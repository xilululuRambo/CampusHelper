package com.rambo.module.admin.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rambo.module.admin.pojo.entity.Admin;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AdminMapper extends BaseMapper<Admin> {
}
