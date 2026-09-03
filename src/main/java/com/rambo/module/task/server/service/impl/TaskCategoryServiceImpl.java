package com.rambo.module.task.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.constants.CacheConstants;
import com.rambo.common.enumType.CategoryStatus;
import com.rambo.module.task.pojo.entity.TaskCategory;
import com.rambo.module.task.pojo.vo.CategoryVO;
import com.rambo.module.task.server.mapper.TaskCategoryMapper;
import com.rambo.module.task.server.service.TaskCategoryService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TaskCategoryServiceImpl extends ServiceImpl<TaskCategoryMapper, TaskCategory> implements TaskCategoryService {
    /**
     * 获取所有任务分类名称
     *
     * @return List<CategoryVO>
     */
    @Override
    @Cacheable(cacheNames = CacheConstants.TASK_CATEGORY_ALL, key = "'all'")
    public List<CategoryVO> getAllCategories() {
        List<TaskCategory> taskCategoryList = lambdaQuery().eq(TaskCategory::getStatus, CategoryStatus.NORMAL).list();
        return taskCategoryList.stream()
                .map(taskCategory -> new CategoryVO(taskCategory.getId(), taskCategory.getName()))
                .collect(Collectors.toList());
    }

    /**
     * 校验任务分类是否存在
     *
     * @param categoryId 分类ID
     * @return 是否存在
     */
    @Override
    public boolean existsById(Long categoryId) {
        return lambdaQuery()
                .eq(TaskCategory::getId, categoryId)
                .eq(TaskCategory::getStatus, CategoryStatus.NORMAL)
                .exists();
    }
}
