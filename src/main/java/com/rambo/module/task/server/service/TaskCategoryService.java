package com.rambo.module.task.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.task.pojo.entity.TaskCategory;
import com.rambo.module.task.pojo.vo.CategoryVO;

import java.util.List;

public interface TaskCategoryService extends IService<TaskCategory> {
    /**
     * 获取所有分类名称
     * @return List<CategoryVO>
     */
    List<CategoryVO> getAllCategories();

    /**
     * 校验任务分类是否存在
     * @param categoryId 分类ID
     * @return 是否存在
     */
    boolean existsById(Long categoryId);
}
