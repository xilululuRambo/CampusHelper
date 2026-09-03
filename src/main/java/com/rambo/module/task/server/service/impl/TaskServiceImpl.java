package com.rambo.module.task.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.module.task.enums.TaskStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.common.context.IdHolder;
import com.rambo.common.result.PageResult;
import com.rambo.module.task.pojo.dto.TaskDTO;
import com.rambo.module.task.pojo.dto.TaskMyQueryDTO;
import com.rambo.module.task.pojo.dto.TaskQueryDTO;
import com.rambo.module.task.pojo.entity.Task;
import com.rambo.module.task.pojo.entity.TaskCategory;
import com.rambo.module.task.pojo.vo.TaskVO;
import com.rambo.module.task.server.mapper.TaskMapper;
import com.rambo.module.task.server.service.TaskCategoryService;
import com.rambo.module.task.server.service.TaskService;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.module.user.pojo.entity.Address;
import com.rambo.module.user.pojo.entity.User;
import com.rambo.module.user.server.service.AddressService;
import com.rambo.module.user.server.service.UserService;
import com.rambo.infrastructure.cache.CacheClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TaskServiceImpl extends ServiceImpl<TaskMapper, Task> implements TaskService {

    @Resource
    private UserService userService;
    @Resource
    private AddressService addressService;
    @Resource
    private TaskCategoryService taskCategoryService;
    @Resource
    private TaskEsSyncService taskEsSyncService;
    @Resource
    private TaskEsSearchService taskEsSearchService;
    @Resource
    private CacheClient cacheClient;

    /**
     * 发布任务
     *
     * @param taskDTO 任务DTO，包含任务信息
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.TASK,
            targetIdEL = "null", action = OperationActionEnum.TASK_PUBLISH, descriptionEL = "'发布任务'")
    public void publishTask(TaskDTO taskDTO) {

        // 1. 校验任务地址是否存在
        if (!addressService.existsById(taskDTO.getAddressId())) {
            throw new BusinessException(MessageConstants.ADDRESS_NOT_FOUND);
        }

        // 2. 校验任务分类是否存在
        if (!taskCategoryService.existsById(taskDTO.getCategoryId())) {
            throw new BusinessException(MessageConstants.CATEGORY_NOT_FOUND);
        }

        // 3. 发布任务
        Task task = BeanUtil.copyProperties(taskDTO, Task.class);
        task.setPublisherId(IdHolder.getId());
        save(task);

        // 4. 保存任务到ES
        taskEsSyncService.syncToEsAsync(task);
        log.info("用户 {} 发布任务成功，taskId={}", IdHolder.getId(), task.getId());
    }

    /**
     * 更新任务
     *
     * @param taskId  任务ID
     * @param taskDTO 任务DTO，包含任务信息
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.TASK,
            targetIdEL = "#taskId", action = OperationActionEnum.TASK_UPDATE, descriptionEL = "'更新任务, taskId=' + #taskId")
    public void updateTask(Long taskId, TaskDTO taskDTO) {

        //校验任务是否存在
        Task task = getById(taskId);
        if (task == null) {
            throw new BusinessException(MessageConstants.TASK_NOT_FOUND);
        }

        //校验任务是否是发布者
        if (!task.getPublisherId().equals(IdHolder.getId())) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }

        //校验任务状态是待接单
        if (task.getStatus() != TaskStatus.PENDING) {
            throw new BusinessException(MessageConstants.TASK_UPDATE_STATUS_ERROR);
        }

        //校验任务地址是否存在
        if (!addressService.existsById(taskDTO.getAddressId())) {
            throw new BusinessException(MessageConstants.ADDRESS_NOT_FOUND);
        }

        //校验任务分类是否存在
        if (!taskCategoryService.existsById(taskDTO.getCategoryId())) {
            throw new BusinessException(MessageConstants.CATEGORY_NOT_FOUND);
        }

        //更新任务
        BeanUtil.copyProperties(taskDTO, task);

        // 乐观锁更新是否成功
        boolean updated = updateById(task);
        if (!updated) {
            // 任务已被其他线程修改，需要抛出业务异常让用户感知
            throw new BusinessException(MessageConstants.TASK_UPDATED_BY_OTHERS);
        }

        //更新任务到ES
        taskEsSyncService.syncToEsAsync(task);
        log.info("用户 {} 更新任务成功，taskId={}", IdHolder.getId(), taskId);
    }

    /**
     * 获取所有任务
     *
     * @param taskQueryDTO 任务查询DTO，包含任务查询信息
     * @return 分页结果，包含所有任务列表
     */
    @Override
    public PageResult<TaskVO> getAllTasks(TaskQueryDTO taskQueryDTO) {
        long pageNum = taskQueryDTO.getPageNum();
        long pageSize = taskQueryDTO.getPageSize();

        // keyword存在：ES 完成 关键词匹配 + 分类/奖励/时间过滤 + 排序 + 分页，数据库仅回查实体
        if (StringUtils.hasText(taskQueryDTO.getKeyword())) {
            // 记录热搜关键词
            cacheClient.zIncrementScore(PrefixConstants.HOT_KEYWORDS, taskQueryDTO.getKeyword(), 1);
            try {
                PageResult<Long> esPage = taskEsSearchService.searchTaskPage(taskQueryDTO.getKeyword(),
                        taskQueryDTO.getCategoryId(), taskQueryDTO.getStartReward(), taskQueryDTO.getEndReward(),
                        taskQueryDTO.getStartTime(), taskQueryDTO.getEndTime(), pageNum, pageSize);

                // 无匹配结果
                if (esPage.getTotal() == 0) {
                    return new PageResult<>(0L, Collections.emptyList());
                }

                // 按 ES 返回的 ID 回查，并保持 ES 排序顺序（in 查询无序）
                List<Task> tasks = lambdaQuery()
                        .in(Task::getId, esPage.getRecords())
                        .eq(Task::getStatus, TaskStatus.PENDING)
                        .list();
                Map<Long, Task> taskMap = tasks.stream()
                        .collect(Collectors.toMap(Task::getId, t -> t));
                // 按 ES 排序顺序回查实体
                List<Task> ordered = esPage.getRecords().stream()
                        .map(taskMap::get)
                        .filter(Objects::nonNull)
                        .toList();

                if (ordered.isEmpty()) {
                    return new PageResult<>(0L, Collections.emptyList());
                }
                return new PageResult<>(esPage.getTotal(), convertToTaskVOList(ordered));
            } catch (IOException e) {
                // ES查询失败，降级数据库查询
                log.error(MessageConstants.ES_OPERATION_FAILED, e);
            }
        }

        // 无关键词 或 ES 降级：数据库分页查询
        var wrapper = lambdaQuery();
        if (StringUtils.hasText(taskQueryDTO.getKeyword())) {
            wrapper.and(w -> w.like(Task::getTitle, taskQueryDTO.getKeyword())
                    .or()
                    .like(Task::getDescription, taskQueryDTO.getKeyword()));
        }

        // 分页构造器
        Page<Task> page = new Page<>(pageNum, pageSize);

        // 分页查询任务
        Page<Task> taskPageList = wrapper
                .eq(Task::getStatus, TaskStatus.PENDING)
                .eq(taskQueryDTO.getCategoryId() != null && taskQueryDTO.getCategoryId() > 0,
                        Task::getCategoryId, taskQueryDTO.getCategoryId())
                .ge(taskQueryDTO.getStartReward() != null && taskQueryDTO.getStartReward() > 0,
                        Task::getReward, taskQueryDTO.getStartReward())
                .le(taskQueryDTO.getEndReward() != null && taskQueryDTO.getEndReward() > 0,
                        Task::getReward, taskQueryDTO.getEndReward())
                .ge(taskQueryDTO.getStartTime() != null,
                        Task::getCreateTime, taskQueryDTO.getStartTime())
                .le(taskQueryDTO.getEndTime() != null,
                        Task::getCreateTime, taskQueryDTO.getEndTime())
                .orderByDesc(Task::getCreateTime)
                .page(page);

        //校验任务列表是否为空
        if (taskPageList.getTotal() == 0) {
            return new PageResult<>(0L, Collections.emptyList());
        }

        List<TaskVO> taskVOList = convertToTaskVOList(taskPageList.getRecords());
        return new PageResult<>(taskPageList.getTotal(), taskVOList);
    }

    /**
     * 获取任务详情
     *
     * @param taskId 任务ID
     * @return 任务VO
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.TASK,
            targetIdEL = "#taskId",
            action = OperationActionEnum.TASK_VIEW_DETAIL, descriptionEL = "'查看任务详情 taskId=' + #taskId")
    public TaskVO getTaskDetail(Long taskId) {

        // 注意：故意不启用 @Cacheable —— 本接口存在按用户视角差异：地址脱敏依赖
        // isParticipant（参与方可见完整地址），若按 taskId 缓存，会把参与方视角的
        // 完整地址提供给非参与方，造成隐私泄露；且 VO 为多源组装（地址/分类/发布者
        // 信息），失效点散落在 workflow/service 两个类 7 个变更方法中，
        // 正确性维护成本超过省一次主键点查的收益
        //校验任务是否存在
        Task task = getById(taskId);
        if (task == null) {
            throw new BusinessException(MessageConstants.TASK_NOT_FOUND);
        }

        //校验任务地址是否存在
        Address address = addressService.getById(task.getAddressId());
        if (address == null) {
            throw new BusinessException(MessageConstants.ADDRESS_NOT_FOUND);
        }

        //转换为VO
        TaskVO taskVO = BeanUtil.copyProperties(task, TaskVO.class);
        taskVO.setStatus(task.getStatus() != null ? task.getStatus().getCode() : null);
        // 地址脱敏：仅任务参与方（发布者/承接者）可见完整地址（含门牌号），其他登录用户只见省市区 + 脱敏门牌
        Long currentUserId = IdHolder.getId();
        boolean participant = currentUserId != null && isParticipant(taskId, currentUserId);
        taskVO.setAddress(buildAddress(address, participant));
        var cat = taskCategoryService.getById(task.getCategoryId());
        if (cat != null) taskVO.setCategoryName(cat.getName());

        //校验发布者用户是否存在
        User user = userService.getById(task.getPublisherId());
        if (user == null) {
            throw new BusinessException(MessageConstants.USER_NOT_EXIST);
        }
        taskVO.setUsername(user.getUsername());
        // objectName 由响应出口 OssUrlResponseBodyAdvice 统一签名
        taskVO.setPublisherAvatar(user.getAvatar());
        log.info("查看任务详情 taskId={}", taskId);
        return taskVO;
    }

    /**
     * 判断用户是否是任务参与人
     *
     * @param taskId 任务ID
     * @param userId 用户ID
     * @return 是否是任务参与人
     */
    @Override
    public boolean isParticipant(Long taskId, Long userId) {
        return lambdaQuery()
                .eq(Task::getId, taskId)
                .and(w -> w
                        .eq(Task::getPublisherId, userId)
                        .or()
                        .eq(Task::getApplicantId, userId))
                .exists();
    }

    /**
     * 获取用户发布的任务
     *
     * @param taskMyQueryDTO 任务查询DTO，包含任务查询信息
     * @return 分页结果，包含用户发布的任务列表
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.TASK,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.TASK_VIEW_MINE, descriptionEL = "'查看我发布的任务'")
    public PageResult<TaskVO> getMyPublishedTasks(TaskMyQueryDTO taskMyQueryDTO) {

        //查询用户发布的任务
        var taskPage = lambdaQuery()
                .eq(Task::getPublisherId, IdHolder.getId());
        PageResult<TaskVO> result = buildPageResult(taskMyQueryDTO, taskPage);
        log.info("用户 {} 查看自己发布的任务列表", IdHolder.getId());
        return result;
    }

    /**
     * 获取用户承接的任务列表
     *
     * @param taskMyQueryDTO 任务查询DTO，包含任务查询信息
     * @return 分页结果，包含用户承接的任务列表
     */
    @Override
    @Log(module = OperationModuleEnum.TASK, targetType = OperationTargetTypeEnum.TASK,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.TASK_VIEW_MINE, descriptionEL = "'查看我承接的任务'")
    public PageResult<TaskVO> getMyAcceptedTasks(TaskMyQueryDTO taskMyQueryDTO) {

        //查询用户承接的任务
        var taskPage = lambdaQuery()
                .eq(Task::getApplicantId, IdHolder.getId());

        PageResult<TaskVO> result = buildPageResult(taskMyQueryDTO, taskPage);
        log.info("用户 {} 查看自己承接的任务列表", IdHolder.getId());
        return result;
    }

    /**
     * 构建任务分页查询条件
     *
     * @param taskMyQueryDTO 任务查询DTO，包含任务查询信息
     * @param taskPage       任务查询条件
     * @return 任务分页列表
     */
    private PageResult<TaskVO> buildPageResult(TaskMyQueryDTO taskMyQueryDTO, LambdaQueryChainWrapper<Task> taskPage) {
        //校验任务状态是否存在
        if (taskMyQueryDTO.getStatus() != null) {
            taskPage.eq(Task::getStatus, taskMyQueryDTO.getStatus());
        }

        //校验任务分类是否存在
        if (taskMyQueryDTO.getCategoryId() != null) {
            taskPage.eq(Task::getCategoryId, taskMyQueryDTO.getCategoryId());
        }

        //构造分页查询
        Page<Task> page = new Page<>(taskMyQueryDTO.getPageNum(), taskMyQueryDTO.getPageSize());

        Page<Task> taskPageList = taskPage.orderByDesc(Task::getCreateTime).page(page);

        //校验任务列表是否为空
        if (taskPageList.getTotal() == 0) {
            return new PageResult<>(0L, Collections.emptyList());
        }

        //转换为VO列表
        List<TaskVO> taskVOList = convertToTaskVOList(taskPageList.getRecords());
        return new PageResult<>(taskPageList.getTotal(), taskVOList);
    }

    /**
     * 转换任务列表为任务VO列表
     *
     * @param tasks 任务列表
     * @return 任务VO列表
     */
    private List<TaskVO> convertToTaskVOList(List<Task> tasks) {

        //批量查询地址id、用户信息id、分类id
        Set<Long> addressIds = tasks.stream().map(Task::getAddressId).collect(Collectors.toSet());
        Set<Long> publisherIds = tasks.stream().map(Task::getPublisherId).collect(Collectors.toSet());
        Set<Long> categoryIds = tasks.stream().map(Task::getCategoryId).collect(Collectors.toSet());

        //批量查询地址信息、用户信息、分类信息
        Map<Long, Address> addressMap = addressService.listByIds(addressIds)
                .stream().collect(Collectors.toMap(Address::getId, Function.identity()));
        Map<Long, User> userMap = userService.listByIds(publisherIds)
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<Long, TaskCategory> categoryMap = taskCategoryService.listByIds(categoryIds)
                .stream().collect(Collectors.toMap(TaskCategory::getId, Function.identity()));

        //转换为VO列表
        return tasks.stream().map(task -> {

            //校验地址和用户是否存在
            Address address = addressMap.get(task.getAddressId());
            User user = userMap.get(task.getPublisherId());
            if (address == null || user == null) {
                log.info("任务 {} 关联地址或用户缺失，跳过", task.getId());
                return null;
            }
            TaskVO taskVO = BeanUtil.copyProperties(task, TaskVO.class);
            taskVO.setStatus(task.getStatus() != null ? task.getStatus().getCode() : null);
            // 列表统一脱敏（匿名可访问），不暴露详细门牌地址
            taskVO.setAddress(buildAddress(address, false));
            taskVO.setUsername(user.getUsername());
            // objectName 由响应出口 OssUrlResponseBodyAdvice 统一签名
            taskVO.setPublisherAvatar(user.getAvatar());
            taskVO.setCategoryName(categoryMap.get(task.getCategoryId()).getName());
            return taskVO;
        }).filter(Objects::nonNull).toList();
    }

    /**
     * 拼接任务地址。
     *
     * @param address     地址实体
     * @param showDetail  是否展示详细门牌地址（仅任务参与方传 true）
     * @return 地址字符串；非参与方对详细地址脱敏，防止匿名批量抓取发布者精确住址
     */
    private String buildAddress(Address address, boolean showDetail) {
        String base = address.getProvince() + address.getCity() + address.getDistrict();
        if (showDetail) {
            return base + address.getDetailAddress();
        }
        return base + maskDetail(address.getDetailAddress());
    }

    /**
     * 详细地址脱敏：末尾门牌号用 ** 覆盖（如 "3号楼502室" → "3号楼5**"），短地址全量打码。
     */
    private String maskDetail(String detail) {
        if (detail == null || detail.isEmpty()) {
            return "";
        }
        if (detail.length() <= 4) {
            return "*".repeat(detail.length());
        }
        return detail.substring(0, detail.length() - 2) + "**";
    }
}