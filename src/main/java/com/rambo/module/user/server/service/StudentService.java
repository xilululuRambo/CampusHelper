package com.rambo.module.user.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.user.pojo.entity.Student;
import com.rambo.module.user.pojo.vo.StudentVO;

public interface StudentService extends IService<Student> {
    /**
     * 获取当前登录用户的学生信息
     * @return 学生信息VO
     */
    StudentVO getStudentInfo();
}