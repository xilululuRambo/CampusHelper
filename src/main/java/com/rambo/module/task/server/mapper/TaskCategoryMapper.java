package com.rambo.module.task.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rambo.module.task.pojo.entity.TaskCategory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskCategoryMapper extends BaseMapper<TaskCategory> {
}
