package com.rambo.module.user.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.constants.CacheConstants;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.common.context.IdHolder;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.module.user.pojo.entity.Student;
import com.rambo.module.user.pojo.vo.StudentVO;
import com.rambo.module.user.server.mapper.StudentMapper;
import com.rambo.module.user.server.service.StudentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class StudentServiceImpl extends ServiceImpl<StudentMapper, Student> implements StudentService {
    /**
     * 获取当前登录用户的学生信息
     * @return 学生信息VO
     */
    @Override
    @Cacheable(cacheNames = CacheConstants.STUDENT_INFO, key = "T(com.rambo.common.context.IdHolder).getId()")
    @Log(module = OperationModuleEnum.USER, targetType = OperationTargetTypeEnum.USER,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.USER_VIEW_STUDENT, descriptionEL = "'查看学生信息'")
    public StudentVO getStudentInfo() {
        // 1. 获取当前登录用户ID
        Long userId = IdHolder.getId();

        // 2. 根据 userId 查询学生信息
        Student student = lambdaQuery().eq(Student::getUserId, userId).one();

        if (student == null) {
            throw new BusinessException(MessageConstants.STUDENT_INFO_NOT_FOUND);
        }

        // 3. 转为VO返回
        StudentVO studentVO = BeanUtil.copyProperties(student, StudentVO.class);
        log.info("用户 {} 查看学生信息", userId);
        return studentVO;
    }
}