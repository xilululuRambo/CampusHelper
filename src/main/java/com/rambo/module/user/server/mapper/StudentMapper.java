package com.rambo.module.user.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rambo.module.user.pojo.entity.Student;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StudentMapper extends BaseMapper<Student> {
}
