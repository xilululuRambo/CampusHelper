package com.rambo.module.admin.server.service;

import com.rambo.common.result.PageResult;
import com.rambo.module.admin.pojo.dto.AdminEvaluationQueryDTO;
import com.rambo.module.admin.pojo.dto.AdminTaskCategoryDTO;
import com.rambo.module.admin.pojo.dto.AdminTaskQueryDTO;
import com.rambo.module.admin.pojo.vo.AdminTaskDetailVO;
import com.rambo.module.admin.pojo.vo.AdminTaskListItemVO;
import com.rambo.module.task.pojo.vo.EvaluationVO;
import jakarta.validation.Valid;

public interface AdminTaskService {
    /**
     * 获取任务列表
     * @param adminTaskQueryDTO 查询参数
     * @return 任务列表分页结果VO
     */
    PageResult<AdminTaskListItemVO> getTaskList(AdminTaskQueryDTO adminTaskQueryDTO);

    /**
     * 获取任务详情
     * @param id 任务ID
     * @return 任务详情
     */
    AdminTaskDetailVO getTaskDetail(Long id);

    /**
     * 下架任务
     * @param id 任务ID
     */
    void downTask(Long id);

    /**
     * 添加任务分类
     * @param categoryDTO 任务分类DTO参数
     */
    void addCategory(AdminTaskCategoryDTO categoryDTO);

    /**
     * 删除任务分类
     * @param id 任务分类ID
     */
    void deleteCategory(Long id);

    /**
     * 修改任务分类
     * @param id 任务分类ID
     * @param categoryDTO 任务分类DTO参数
     */
    void updateCategory(Long id, @Valid AdminTaskCategoryDTO categoryDTO);

    /**
     * 分页查询全站任务评价
     * @param queryDTO 查询参数
     * @return 评价分页结果
     */
    PageResult<EvaluationVO> getEvaluationList(AdminEvaluationQueryDTO queryDTO);

    /**
     * 删除评价
     * @param id 评价ID
     */
    void deleteEvaluation(Long id);
}
