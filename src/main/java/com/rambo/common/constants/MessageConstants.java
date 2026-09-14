package com.rambo.common.constants;

public class MessageConstants {
    // ==================== 通用 ====================
    public static final String SUCCESS = "操作成功";
    public static final String FAIL = "操作失败";
    public static final String SYSTEM_BUSY = "系统繁忙，请稍后再试";
    public static final String SYSTEM_ERROR = "系统错误，请稍后再试";
    public static final String NO_PERMISSION = "无权操作";
    public static final String PARAM_ERROR = "参数错误";
    public static final String DUPLICATE_SUBMIT = "请勿重复提交";
    // 乐观锁冲突提示（按场景）
    public static final String TASK_STATUS_CHANGED = "该任务状态已发生变更，请刷新页面后重试";
    public static final String APPLICATION_STATUS_CHANGED = "该申请已被处理，请刷新页面后重试";
    public static final String TASK_UPDATED_BY_OTHERS = "任务信息已被其他人修改，请刷新后重新编辑";
    // ==================== 用户/认证 ====================
    public static final String PHONE_ERROR = "手机号格式错误";
    public static final String CODE_ERROR = "验证码错误";
    // 验证码错误次数达上限（当前验证码已作废，需重新获取后才能继续尝试）
    public static final String CODE_FAIL_LIMIT = "验证码错误次数过多，请重新获取";
    public static final String USER_NOT_EXIST = "用户不存在";
    public static final String UNAUTHORIZED = "未登录或登录已失效，请重新登录";
    public static final String CODE_COOLDOWN_ERROR = "验证码冷却中，请稍后再试";
    public static final String NOT_AUTH = "请完成学生认证后再操作";
    public static final String USER_CONFIRMED = "用户已认证，无需重复认证";
    public static final String USER_DISABLED = "账号已被禁用，请联系管理员";
    public static final String STUDENT_AUTH_FAILED = "学生认证失败";
    public static final String STUDENT_IS_CONFIRMED = "该学号已被认证";
    public static final String STUDENT_INFO_NOT_FOUND = "学生信息不存在";
    public static final String BALANCE_NOT_ENOUGH = "余额不足";
    public static final String UPDATE_INFO_EMPTY = "更新信息不能为空，请至少提供用户名或头像";
    public static final String USERNAME_EXIST = "用户名已被占用";
    public static final String PHONE_EXIST = "手机号已被占用";
    public static final String USERNAME_INVALID = "用户名格式错误";
    public static final String NAME_INVALID = "姓名格式错误";
    public static final String UNIQUE_CONFLICT = "用户名或手机号已被占用";
    public static final String POINTS_INSUFFICIENT = "积分不足以扣减";
    public static final String CREDIT_OUT_OF_RANGE = "信誉分需保持在0-100之间";
    public static final String ADMIN_USER_UPDATE_EMPTY = "至少提供一个可修改字段（用户名/手机号/真实姓名）";
    public static final String USER_UPDATE_CONFLICT = "用户信息已被其他人修改，请刷新后重试";

    // ==================== 文件 ====================
    public static final String FILE_UPLOAD_FAILED = "文件上传失败";
    public static final String FILE_DELETE_FAILED = "文件删除失败";
    public static final String FILE_EMPTY = "文件不能为空";
    public static final String GOODS_IMAGE_FORMAT_INVALID = "商品图片格式错误";
    public static final String GOODS_IMAGE_SIZE_ERROR = "商品图片不能超过10MB";
    public static final String GOODS_IMAGE_MAX_COUNT = "商品图片最多上传9张";
    public static final String GOODS_IMAGE_EMPTY = "商品图片不能为空";
    public static final String AVATAR_EMPTY = "头像不能为空";
    public static final String AVATAR_SIZE_ERROR = "头像不能超过2MB";
    public static final String AVATAR_FORMAT_ERROR = "头像格式错误";
    public static final String EVIDENCE_EMPTY = "任务完成证据不能为空";
    public static final String EVIDENCE_SIZE_ERROR = "任务完成证据不能超过10MB";
    public static final String EVIDENCE_FORMAT_ERROR = "任务完成证据格式错误";

    // ==================== 地址 ====================
    public static final String ADDRESS_NOT_FOUND = "地址不存在";
    public static final String ADDRESS_MAX_COUNT = "最多只能保存3个地址";

    // ==================== 任务 ====================
    public static final String TASK_NOT_FOUND = "任务不存在";
    public static final String TASK_ORDER_EXISTS = "任务已存在订单";
    public static final String TASK_STATUS_ERROR = "任务状态错误，无法执行当前操作";
    public static final String TASK_CANCEL_STATUS_ERROR = "任务状态错误，无法取消";
    public static final String TASK_UPDATE_STATUS_ERROR = "只有待处理状态的任务才能更新";
    public static final String TASK_NOT_FINISHED = "任务未完成，无法评价";
    public static final String TASK_APPLICATION_PUBLISHER_ERROR = "不能申请自己发布的任务";
    public static final String TASK_CANCELLED = "任务已被取消";
    public static final String TASK_NOT_ACCEPTED = "任务尚未被承接，无法发起会话";
    public static final String CHAT_RECEIVER_NOT_FOUND = "无法确定消息接收者，请确认对方已承接/参与该会话";

    // ==================== 任务申请 ====================
    public static final String TASK_APPLICATION_NOT_FOUND = "任务申请不存在";
    public static final String TASK_APPLICATION_SELF_ERROR = "不能申请自己发布的任务";
    public static final String TASK_APPLICATION_DUPLICATE_ERROR = "您已申请过该任务，请勿重复申请";
    public static final String TASK_APPLICATION_PROCESS_ERROR = "该申请已处理，请勿重复操作";
    public static final String TASK_APPLICATION_CANCEL_ERROR = "只能取消待处理的申请";
    public static final String TASK_APPLICATION_REJECTED_ERROR = "您的申请已被拒绝，无法重复申请";

    // ==================== 任务订单 ====================
    public static final String ORDER_NOT_FOUND = "订单不存在";

    // ==================== 任务评价 ====================
    public static final String EVALUATION_EXISTED = "您已评价过该订单";
    public static final String EVALUATION_NOT_FOUND = "评价不存在";

    // ==================== 任务分类 ====================
    public static final String CATEGORY_NOT_FOUND = "任务分类不存在";
    public static final String CATEGORY_NAME_EXIST = "任务分类名称已存在";
    public static final String CATEGORY_HAS_TASKS = "该分类下存在任务，无法删除";

    // ==================== ES ====================
    public static final String ES_OPERATION_FAILED = "ES操作失败";

    // ==================== 商品 ====================
    public static final String GOODS_NOT_FOUND = "商品不存在";
    public static final String GOODS_STATUS_IS_INVALID = "商品被锁定，无法执行当前操作";
    public static final String GOODS_DISABLED_BY_ADMIN = "商品已被管理员下架，请联系客服";
    public static final String GOODS_ID_EMPTY = "商品ID不能为空";
    public static final String BUYER_ID_EMPTY = "买家ID不能为空";
    public static final String OWNER_ID_EMPTY = "卖家ID不能为空";
    public static final String ORDER_ID_EMPTY = "订单ID不能为空";
    public static final String GOODS_TITLE_EMPTY = "商品标题不能为空";
    public static final String DESCRIPTION_EMPTY = "商品描述不能为空";
    public static final String IMAGES_EMPTY = "商品图片不能为空";
    public static final String PRICE_EMPTY = "商品单价不能为空或小于0";
    public static final String TOTAL_AMOUNT_EMPTY = "金额不能为空或小于0";
    public static final String GOODS_BUYED = "商品已经被购买，不能重复购买";


    // ==================== 商品订单 ====================
    public static final String GOODS_ORDER_STATUS_ERROR = "商品订单状态错误，无法执行当前操作";
    public static final String GOODS_ORDER_NOT_FOUND = "商品订单不存在";
    public static final String GOODS_ORDER_EXPIRED = "订单已超时关闭，请重新下单";
    public static final String GOODS_ORDER_PAY_ERROR = "商品订单支付失败";
    public static final String GOODS_ORDER_CANCEL_ERROR = "商品订单取消失败";
    public static final String GOODS_ORDER_DELIVERY_ERROR = "商品订单发货失败";
    public static final String GOODS_ORDER_CONFIRM_ERROR = "商品订单确认收货失败";
    public static final String GOODS_ORDER_REFUND_ERROR = "退款失败：卖家余额不足，请联系平台人工处理";
    public static final String BUYER_GOODS_ORDER_CANCEL_REFUND = "订单已被管理员取消，款项已退回余额";
    public static final String OWNER_GOODS_ORDER_CANCEL_REFUND = "订单已被管理员取消，款项已退回买家";
    public static final String BUYER_GOODS_ORDER_CANCEL = "订单已被管理员取消";
    public static final String OWNER_GOODS_ORDER_CANCEL = "你的订单已被管理员取消";

    // ==================== 商品快照项 ====================
    public static final String GOODS_ORDER_ITEM_NOT_FOUND = "商品快照不存在";


    //==================== 商品分类 ====================
    public static final String GOODS_CATEGORY_NOT_FOUND = "商品分类不存在";
    public static final String GOODS_CATEGORY_NAME_EXIST = "商品分类名称已存在";
    public static final String GOODS_CATEGORY_STATUS_ERROR = "商品分类状态错误，无法执行当前操作";
    public static final String GOODS_CATEGORY_HAS_GOODS = "该分类下存在商品，无法删除";

    // ==================== 商品评价 ====================
    public static final String GOODS_ORDER_NOT_COMPLETED = "商品订单未完成，不能评价";
    public static final String GOODS_EVALUATION_EXISTED = "您已评价过该订单";
    public static final String GOODS_EVALUATION_SCORE_ERROR = "评分必须在1-5之间";
    public static final String GOODS_EVALUATION_CONTENT_EMPTY = "评价内容不能为空";
    public static final String GOODS_EVALUATION_TO_USER_ERROR = "不能评价自己";
    public static final String GOODS_EVALUATION_NOT_FOUND = "商品评价不存在";


    //=======================   消息内容    ==============
    public static final String GOODS_ORDERED = "你有新的商品订单";
    public static final String BUYER_GOODS_ORDER_PAY_SUCCESS = "商品订单支付成功";
    public static final String OWNER_GOODS_ORDER_PAY_SUCCESS = "买家已支付商品订单，请尽快发货";
    public static final String GOODS_ORDER_DELIVER_SUCCESS = "商品订单发货成功";
    public static final String GOODS_ORDER_COMPLETE_SUCCESS = "商品订单已完成";
    public static final String GOODS_EVALUATION_SUCCESS = "你有新的商品评价";
    public static final String TASK_APPLICATION_SUCCESS = "你有新的任务申请";
    public static final String TASK_APPLICATION_AGREE_SUCCESS = "你的任务申请已被同意";
    public static final String TASK_APPLICATION_REJECT_SUCCESS = "你的任务申请已被拒绝";
    public static final String TASK_APPLICATION_COMPLETE_SUCCESS = "你的任务已完成,请确认任务是否完成";
    public static final String TASK_COMPLETE_SUCCESS = "任务已完成";
    public static final String TASK_EVALUATION_SUCCESS = "你有新的任务评价";
    public static final String NOTIFICATION_NOT_FOUND = "通知不存在";
    // 管理员调整用户积分/信誉分通知文案（%s：带符号变动值，如 +100/-50；%s：调整原因）
    public static final String POINTS_ADJUST_NOTICE = "管理员调整了你的积分：%s，原因：%s";
    public static final String CREDIT_ADJUST_NOTICE = "管理员调整了你的信誉分：%s，原因：%s";

    // ==================== 会话 ====================
    public static final String MISSING_SESSION_ID = "缺少 sessionId 头";
    public static final String TASK_APPLICATION_AGREE_SESSION = "任务申请已同意，请尽快开始任务。";
    public static final String GOODS_ORDER_AGREE_SESSION = "感谢你的购买，我们会尽快发货。";
    public static final String SESSION_NOT_FOUND = "会话不存在";

    // ==================== 管理员 ====================
    public static final String ADMIN_LOGIN_ERROR = "账号或密码错误";
    public static final String ADMIN_ACCOUNT_INVALID = "账号格式错误";
    public static final String ADMIN_PASSWORD_INVALID = "密码格式错误";
    public static final String ADMIN_PASSWORD_EMPTY = "密码不能为空";
    public static final String ADMIN_ACCOUNT_EXIST = "账号已存在";
    public static final String ADMIN_NAME_INVALID = "姓名格式错误";
    public static final String ADMIN_PHONE_INVALID = "手机号格式错误";
    public static final String ADMIN_STATUS_INVALID = "管理员状态错误";
    public static final String ADMIN_DISABLED = "该管理员已被禁用";
    public static final String ADMIN_NOT_FOUND = "管理员不存在";
    public static final String ADMIN_LOGIN_LOCKED = "失败次数过多，账号已锁定，请15分钟后再试";
    public static final String ADMIN_CANNOT_OPERATE_SELF = "不能操作自己的账号";
}