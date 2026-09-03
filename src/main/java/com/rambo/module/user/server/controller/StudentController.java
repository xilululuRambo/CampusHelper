package com.rambo.module.user.server.controller;

import com.rambo.common.annotation.NoAuthAnnotation;
import com.rambo.common.result.Result;
import com.rambo.module.user.pojo.vo.StudentVO;
import com.rambo.module.user.server.service.StudentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/student")
@RequiredArgsConstructor
@Tag(name = "学生信息接口", description = "学生信息相关接口")
public class StudentController {

    private final StudentService studentService;

    /**
     * 获取当前登录用户的学生信息
     * @return 学生信息VO
     */
    @NoAuthAnnotation
    @Operation(summary = "获取当前登录用户的学生信息")
    @GetMapping("/info")
    public Result<StudentVO> getStudentInfo() {
        log.info("获取当前登录用户的学生信息");
        StudentVO studentVO = studentService.getStudentInfo();
        return Result.success(studentVO);
    }
}