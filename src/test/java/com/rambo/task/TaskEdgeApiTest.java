package com.rambo.task;

import com.rambo.BaseApiTest;
import com.rambo.helper.AuthUser;
import com.rambo.module.task.pojo.entity.HotKeywords;
import com.rambo.module.task.pojo.entity.RankTaskMonthly;
import com.rambo.module.task.pojo.entity.TaskOrder;
import com.rambo.module.task.server.job.HotKeywordsArchiveJob;
import com.rambo.module.task.server.job.TaskRankArchiveJob;
import com.rambo.module.task.server.service.HotKeywordsService;
import com.rambo.module.task.server.service.RankTaskMonthlyService;
import com.rambo.module.task.server.service.TaskOrderService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static com.rambo.helper.AssertHelper.assertOk;
import static com.rambo.helper.AssertHelper.assertUnauthorized;
import static com.rambo.helper.AssertHelper.assertFailWithMsg;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务模块边界/安全测试（独立于 TaskApiTest / TaskApplicationApiTest 之外的补充面）：
 *   1. 排行榜：当前月走 Redis ZSet（顺序+排名）、非当前月走 DB、总榜、空榜
 *   2. 热搜关键词：ZSet 倒序 + 次数
 *   3. 任务分类：@NoAuthAnnotation 无需登录
 *   4. 任务评价查看 IDOR 防护：参与方可见 / 第三方无权 / 未登录拒绝 / 订单不存在
 *   5. 申请拒绝分支：拒绝后不可重复申请、非发布者拒绝越权
 *   6. TaskRankArchiveJob：上月 Top10 归档入库 + 归档 key 清理 + 空榜安全返回
 *   7. HotKeywordsArchiveJob：昨日 Top 归档入库 + 跨天遗留孤儿按真实日期收编
 */
class TaskEdgeApiTest extends BaseApiTest {

    @Resource
    private RankTaskMonthlyService rankTaskMonthlyService;
    @Resource
    private TaskRankArchiveJob taskRankArchiveJob;
    @Resource
    private TaskOrderService taskOrderService;
    @Resource
    private HotKeywordsArchiveJob hotKeywordsArchiveJob;
    @Resource
    private HotKeywordsService hotKeywordsService;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private String currentMonth() {
        return LocalDate.now().format(MONTH_FMT);
    }

    private String lastMonth() {
        return LocalDate.now().minusMonths(1).format(MONTH_FMT);
    }

    /** 更早的月份，用于与归档 Job（固定用 lastMonth）隔离，避免用例间数据污染 */
    private String monthsAgo(int n) {
        return LocalDate.now().minusMonths(n).format(MONTH_FMT);
    }

    private String monthRankKey(String month) {
        return "task_rank:month:" + month;
    }

    private String archiveKey(String month) {
        return "task_rank:month:archive" + month;
    }

    private String hotArchiveKey(LocalDate date) {
        return "hot:keywords:archive:" + date;
    }

    @BeforeEach
    @AfterEach
    void cleanRankKeys() {
        redis.delete(monthRankKey(currentMonth()));
        redis.delete(monthRankKey(lastMonth()));
        redis.delete(archiveKey(lastMonth()));
        redis.delete("task_rank:total");
        redis.delete("hot:keywords");
        // 清理全部热搜归档 key（含跨天孤儿残留），保证用例间隔离
        Set<String> hotArchives = redis.keys("hot:keywords:archive:*");
        if (hotArchives != null && !hotArchives.isEmpty()) {
            redis.delete(hotArchives);
        }
    }

    // ==================== 排行榜 ====================

    @Test
    @DisplayName("排行榜-当前月：Redis ZSet 按分数倒序返回，rankNum 从 1 递增")
    void monthlyRank_currentMonth_fromZset() {
        AuthUser user = newAuthedUser();
        String key = monthRankKey(currentMonth());
        redis.opsForZSet().add(key, "10001", 5);
        redis.opsForZSet().add(key, "10002", 10);
        redis.opsForZSet().add(key, "10003", 3);

        ResponseEntity<Map> resp = get("/task/rank/monthly/monthly?month=" + currentMonth(), user);
        assertOk(resp);
        List<Map<String, Object>> list = (List<Map<String, Object>>) resp.getBody().get("data");
        assertThat(list).hasSize(3);
        assertThat(String.valueOf(list.get(0).get("userId"))).isEqualTo("10002");
        assertThat(String.valueOf(list.get(1).get("userId"))).isEqualTo("10001");
        assertThat(String.valueOf(list.get(2).get("userId"))).isEqualTo("10003");
        // rankNum 按顺序 1,2,3；finishCount 对应分数
        assertThat(((Number) list.get(0).get("rankNum")).intValue()).isEqualTo(1);
        assertThat(((Number) list.get(0).get("finishCount")).intValue()).isEqualTo(10);
        assertThat(((Number) list.get(2).get("rankNum")).intValue()).isEqualTo(3);
    }

    @Test
    @DisplayName("排行榜-非当前月：从 DB 查询归档记录，按 finishCount 倒序")
    void monthlyRank_pastMonth_fromDb() {
        AuthUser user = newAuthedUser();
        // 用上上月与归档 Job 隔离；先清该月残留，保证 size 断言稳定
        String month = monthsAgo(2);
        rankTaskMonthlyService.lambdaUpdate()
                .eq(RankTaskMonthly::getMonth, month).remove();
        // 唯一键 uk_user_month，随机 userId 保证重复执行不冲突
        long uidA = ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
        long uidB = uidA + 1;
        rankTaskMonthlyService.save(buildRank(uidA, month, 8, 2));
        rankTaskMonthlyService.save(buildRank(uidB, month, 12, 1));

        ResponseEntity<Map> resp = get("/task/rank/monthly/monthly?month=" + month, user);
        assertOk(resp);
        List<Map<String, Object>> list = (List<Map<String, Object>>) resp.getBody().get("data");
        assertThat(list).hasSize(2);
        assertThat(String.valueOf(list.get(0).get("userId"))).isEqualTo(String.valueOf(uidB)); // 12 > 8
        assertThat(((Number) list.get(0).get("finishCount")).intValue()).isEqualTo(12);
        assertThat(((Number) list.get(0).get("rankNum")).intValue()).isEqualTo(1);
        assertThat(String.valueOf(list.get(1).get("userId"))).isEqualTo(String.valueOf(uidA));
    }

    @Test
    @DisplayName("排行榜-总榜：Redis ZSet 倒序返回全部")
    void totalRank_zsetOrdered() {
        AuthUser user = newAuthedUser();
        String key = "task_rank:total";
        redis.opsForZSet().add(key, "20001", 1);
        redis.opsForZSet().add(key, "20002", 7);
        redis.opsForZSet().add(key, "20003", 4);

        ResponseEntity<Map> resp = get("/task/rank/monthly/total", user);
        assertOk(resp);
        List<Map<String, Object>> list = (List<Map<String, Object>>) resp.getBody().get("data");
        assertThat(list).hasSize(3);
        assertThat(String.valueOf(list.get(0).get("userId"))).isEqualTo("20002");
        assertThat(String.valueOf(list.get(2).get("userId"))).isEqualTo("20001");
        assertThat(((Number) list.get(0).get("rankNum")).intValue()).isEqualTo(1);
    }

    @Test
    @DisplayName("排行榜-空榜：无数据返回空列表而非报错")
    void monthlyRank_empty_returnsEmpty() {
        AuthUser user = newAuthedUser();
        ResponseEntity<Map> resp = get("/task/rank/monthly/monthly?month=" + currentMonth(), user);
        assertOk(resp);
        assertThat((List<?>) resp.getBody().get("data")).isEmpty();
    }

    // ==================== 热搜关键词 ====================

    @Test
    @DisplayName("热搜：ZSet 按搜索次数倒序返回 Top10")
    void hotKeywords_zsetOrdered() {
        AuthUser user = newAuthedUser();
        String key = "hot:keywords";
        redis.opsForZSet().add(key, "取快递", 12);
        redis.opsForZSet().add(key, "代课", 30);
        redis.opsForZSet().add(key, "打印", 5);

        ResponseEntity<Map> resp = get("/task/hot/keywords", user);
        assertOk(resp);
        List<Map<String, Object>> list = (List<Map<String, Object>>) resp.getBody().get("data");
        assertThat(list).hasSize(3);
        assertThat(String.valueOf(list.get(0).get("keyword"))).isEqualTo("代课");
        assertThat(((Number) list.get(0).get("searchCount")).intValue()).isEqualTo(30);
        assertThat(String.valueOf(list.get(2).get("keyword"))).isEqualTo("打印");
    }

    // ==================== HotKeywordsArchiveJob 热搜归档 ====================

    @Test
    @DisplayName("热搜归档 Job：昨日 Top 入库且顺序保持，归档 key 清理")
    void hotKeywordsArchiveJob_archivesYesterday() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        // 清理该日期残留，保证断言稳定；keyword 随机后缀避免撞唯一键 uk_keyword_date
        hotKeywordsService.lambdaUpdate().eq(HotKeywords::getRecordDate, yesterday).remove();
        String kw = "归档-" + ThreadLocalRandom.current().nextInt(10000, 99999);
        redis.opsForZSet().add("hot:keywords", kw + "-c", 5);
        redis.opsForZSet().add("hot:keywords", kw + "-a", 30);
        redis.opsForZSet().add("hot:keywords", kw + "-b", 12);

        hotKeywordsArchiveJob.execute();

        // 归档 key 已清理，当天 key 已被 rename 走
        assertThat(Boolean.TRUE.equals(redis.hasKey(hotArchiveKey(yesterday)))).isFalse();
        assertThat(Boolean.TRUE.equals(redis.hasKey("hot:keywords"))).isFalse();

        // DB 已入库且日期为昨天，按搜索次数倒序保持
        List<HotKeywords> saved = hotKeywordsService.lambdaQuery()
                .eq(HotKeywords::getRecordDate, yesterday)
                .orderByDesc(HotKeywords::getSearchCount)
                .list();
        assertThat(saved).hasSize(3);
        assertThat(saved.get(0).getKeyword()).isEqualTo(kw + "-a");
        assertThat(saved.get(0).getSearchCount()).isEqualTo(30);
        assertThat(saved.get(2).getKeyword()).isEqualTo(kw + "-c");
        assertThat(saved.get(2).getSearchCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("热搜归档 Job：跨天遗留孤儿按真实日期收编，当天数据不受影响")
    void hotKeywordsArchiveJob_orphanCollectedWithRealDate() {
        // 模拟上次失败残留：3 天前 RENAME 成功但入库失败（孤儿归档 key 自带真实日期）
        LocalDate orphanDate = LocalDate.now().minusDays(3);
        hotKeywordsService.lambdaUpdate().eq(HotKeywords::getRecordDate, orphanDate).remove();
        String orphanKw = "遗留-" + ThreadLocalRandom.current().nextInt(10000, 99999);
        redis.opsForZSet().add(hotArchiveKey(orphanDate), orphanKw, 8);

        // 昨天（本次应归档日期）有新数据
        LocalDate yesterday = LocalDate.now().minusDays(1);
        hotKeywordsService.lambdaUpdate().eq(HotKeywords::getRecordDate, yesterday).remove();
        String todayKw = "当天-" + ThreadLocalRandom.current().nextInt(10000, 99999);
        redis.opsForZSet().add("hot:keywords", todayKw, 3);

        hotKeywordsArchiveJob.execute();

        // 孤儿数据以真实日期（3 天前）入库，而非被记到昨天
        List<HotKeywords> orphan = hotKeywordsService.lambdaQuery()
                .eq(HotKeywords::getRecordDate, orphanDate).list();
        assertThat(orphan).hasSize(1);
        assertThat(orphan.get(0).getKeyword()).isEqualTo(orphanKw);
        assertThat(orphan.get(0).getRecordDate()).isEqualTo(orphanDate);
        assertThat(Boolean.TRUE.equals(redis.hasKey(hotArchiveKey(orphanDate)))).isFalse();

        // 当天数据正常归档（不被孤儿幂等误判丢弃）
        List<HotKeywords> today = hotKeywordsService.lambdaQuery()
                .eq(HotKeywords::getRecordDate, yesterday).list();
        assertThat(today).hasSize(1);
        assertThat(today.get(0).getKeyword()).isEqualTo(todayKw);
        assertThat(Boolean.TRUE.equals(redis.hasKey(hotArchiveKey(yesterday)))).isFalse();
    }

    // ==================== 任务分类 ====================

    @Test
    @DisplayName("任务分类：@NoAuthAnnotation 仅需登录（不要求认证），登录即可访问且返回分类")
    void categoryList_noAuthAccessible() {
        ResponseEntity<Map> resp = get("/task/category", newAuthedUser());
        assertOk(resp);
        List<Map<String, Object>> list = (List<Map<String, Object>>) resp.getBody().get("data");
        assertThat(list).isNotEmpty();
        assertThat(String.valueOf(list.get(0).get("name"))).isNotBlank();
    }

    // ==================== 任务评价查看 IDOR ====================

    @Test
    @DisplayName("评价查看 IDOR：参与方可看，第三方无权，未登录拒绝，订单不存在")
    void evaluationView_idor_protected() {
        // 完整链路生成已完成任务订单 + 双方互评
        AuthUser publisher = newAuthedUser();
        AuthUser applicant = newAuthedUser();
        Long addressId = createAddress(publisher);
        Long taskId = publishAndGetId(publisher, addressId);
        assertOk(postWithSubmitToken("/task/application/" + taskId + "?reason=申请", null, applicant, "applyForTask"));
        Long applicationId = myLatestApplicationId(applicant, taskId);
        assertOk(put("/task/application/" + applicationId + "/accept", null, publisher));
        assertOk(putMultipart("/task/application/" + applicationId + "/confirm-complete", multipartEvidence(), applicant));
        assertOk(put("/task/" + taskId + "/confirm-complete", null, publisher));

        // 双方互评
        Map<String, Object> ev = new HashMap<>();
        ev.put("orderId", taskOrderId(taskId));
        ev.put("score", 5);
        ev.put("content", "好评-边缘测试");
        assertOk(post("/task/evaluation", ev, applicant));
        assertOk(post("/task/evaluation", ev, publisher));

        Long orderId = taskOrderId(taskId);

        // 参与方可查看
        ResponseEntity<Map> byPublisher = get("/task/evaluation/order/" + orderId, publisher);
        assertOk(byPublisher);
        List<Map<String, Object>> records = (List<Map<String, Object>>)
                ((Map<?, ?>) byPublisher.getBody().get("data")).get("records");
        assertThat(records).hasSize(2);
        ResponseEntity<Map> byApplicant = get("/task/evaluation/order/" + orderId, applicant);
        assertOk(byApplicant);

        // 第三方无权（IDOR 防护）
        AuthUser stranger = newAuthedUser();
        assertFailWithMsg(get("/task/evaluation/order/" + orderId, stranger), "无权");

        // 未登录拒绝
        assertUnauthorized(get("/task/evaluation/order/" + orderId, null));

        // 订单不存在
        assertFailWithMsg(get("/task/evaluation/order/987654321012", publisher), "订单不存在");
    }

    // ==================== 申请拒绝分支 ====================

    @Test
    @DisplayName("申请拒绝：发布者拒绝后申请者不可再次申请")
    void rejectApplication_rejectedCannotReapply() {
        AuthUser publisher = newAuthedUser();
        AuthUser applicant = newAuthedUser();
        Long addressId = createAddress(publisher);
        Long taskId = publishAndGetId(publisher, addressId);
        assertOk(postWithSubmitToken("/task/application/" + taskId + "?reason=试试", null, applicant, "applyForTask"));
        Long applicationId = myLatestApplicationId(applicant, taskId);

        // 发布者拒绝
        assertOk(put("/task/application/" + applicationId + "/reject", null, publisher));

        // 拒绝后再次申请 → 已被拒绝
        assertFailWithMsg(postWithSubmitToken("/task/application/" + taskId + "?reason=再试", null, applicant, "applyForTask"), "拒绝");
    }

    @Test
    @DisplayName("申请拒绝：非发布者拒绝越权被拒")
    void reject_byNonPublisher_denied() {
        AuthUser publisher = newAuthedUser();
        AuthUser applicant = newAuthedUser();
        AuthUser stranger = newAuthedUser();
        Long addressId = createAddress(publisher);
        Long taskId = publishAndGetId(publisher, addressId);
        assertOk(postWithSubmitToken("/task/application/" + taskId + "?reason=申请", null, applicant, "applyForTask"));
        Long applicationId = myLatestApplicationId(applicant, taskId);

        assertFailWithMsg(put("/task/application/" + applicationId + "/reject", null, stranger), "无权");
    }

    // ==================== TaskRankArchiveJob 归档 ====================

    @Test
    @DisplayName("归档 Job：上月 Top10 写入 DB，归档 key 清理，排名顺序保持")
    void archiveJob_archivesTop10() {
        String month = lastMonth();
        String key = monthRankKey(month);
        // 随机 userId，避免重复执行撞 uk_user_month 唯一键；分数故意乱序验证按分数倒序取 Top
        long base = ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
        String u1 = String.valueOf(base);
        String u2 = String.valueOf(base + 1);
        String u3 = String.valueOf(base + 2);
        redis.opsForZSet().add(key, u1, 3);
        redis.opsForZSet().add(key, u2, 10);
        redis.opsForZSet().add(key, u3, 7);

        taskRankArchiveJob.execute();

        // 归档 key 已清理
        assertThat(Boolean.TRUE.equals(redis.hasKey(archiveKey(month)))).isFalse();
        // 原 key 已被 rename 走，也不再存在
        assertThat(Boolean.TRUE.equals(redis.hasKey(key))).isFalse();

        // DB 已入库且按排名
        List<RankTaskMonthly> saved = rankTaskMonthlyService.lambdaQuery()
                .eq(RankTaskMonthly::getMonth, month)
                .in(RankTaskMonthly::getUserId, base, base + 1, base + 2)
                .orderByAsc(RankTaskMonthly::getRankNum)
                .list();
        assertThat(saved).hasSize(3);
        assertThat(saved.get(0).getUserId()).isEqualTo(base + 1);
        assertThat(saved.get(0).getFinishCount()).isEqualTo(10);
        assertThat(saved.get(0).getRankNum()).isEqualTo(1);
        assertThat(saved.get(2).getUserId()).isEqualTo(base);
        assertThat(saved.get(2).getRankNum()).isEqualTo(3);
    }

    @Test
    @DisplayName("归档 Job：上月无榜单（key 不存在）安全跳过，不报错不新增数据")
    void archiveJob_emptyRank_safe() {
        String month = lastMonth();
        // 前置清理已删除 key → 模拟"上月无人上榜"
        long before = rankTaskMonthlyService.lambdaQuery()
                .eq(RankTaskMonthly::getMonth, month).count();
        taskRankArchiveJob.execute(); // 不应抛异常
        long after = rankTaskMonthlyService.lambdaQuery()
                .eq(RankTaskMonthly::getMonth, month).count();
        assertThat(after).isEqualTo(before); // 未新增任何归档记录
    }

    @Test
    @DisplayName("归档 Job：孤儿归档 key 收编（上次入库失败的残留），入库后清理 key")
    void archiveJob_collectsOrphanKey() {
        String month = lastMonth();
        String newKey = archiveKey(month);
        // 只预置归档 key，模拟"上次 RENAME 成功但入库失败"：当月 key 已不存在，孤儿 key 滞留
        long base = ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
        redis.opsForZSet().add(newKey, String.valueOf(base), 9);
        redis.opsForZSet().add(newKey, String.valueOf(base + 1), 4);

        taskRankArchiveJob.execute();

        // 孤儿 key 被收编清理
        assertThat(Boolean.TRUE.equals(redis.hasKey(newKey))).isFalse();
        // 数据已入库且保持分数倒序
        List<RankTaskMonthly> saved = rankTaskMonthlyService.lambdaQuery()
                .eq(RankTaskMonthly::getMonth, month)
                .in(RankTaskMonthly::getUserId, base, base + 1)
                .orderByAsc(RankTaskMonthly::getRankNum)
                .list();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getUserId()).isEqualTo(base);
        assertThat(saved.get(0).getFinishCount()).isEqualTo(9);
    }

    @Test
    @DisplayName("归档 Job：幂等防重——归档 key 残留但 DB 已入库，跳过重复插入仅清理 key")
    void archiveJob_idempotentSkipDuplicate() {
        String month = lastMonth();
        String newKey = archiveKey(month);
        long uid = ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
        // 模拟"入库成功但删 key 前进程崩溃"：DB 已有该用户记录 + 归档 key 残留
        rankTaskMonthlyService.save(buildRank(uid, month, 9, 1));
        redis.opsForZSet().add(newKey, String.valueOf(uid), 9);

        taskRankArchiveJob.execute();

        // 归档 key 清理，且未撞 uk_user_month 重复插入
        assertThat(Boolean.TRUE.equals(redis.hasKey(newKey))).isFalse();
        long count = rankTaskMonthlyService.lambdaQuery()
                .eq(RankTaskMonthly::getMonth, month)
                .eq(RankTaskMonthly::getUserId, uid)
                .count();
        assertThat(count).isEqualTo(1);
    }

    // ==================== 私有工具 ====================

    private RankTaskMonthly buildRank(long userId, String month, int finishCount, int rankNum) {
        RankTaskMonthly r = new RankTaskMonthly();
        r.setUserId(userId);
        r.setMonth(month);
        r.setFinishCount(finishCount);
        r.setRankNum(rankNum);
        return r;
    }

    private Map<String, Object> multipartEvidence() {
        Map<String, Object> form = new HashMap<>();
        form.put("completeEvidence", new org.springframework.core.io.ByteArrayResource("evidence".getBytes()) {
            @Override
            public String getFilename() {
                return "evidence.jpg";
            }
        });
        return form;
    }

    private Long createAddress(AuthUser user) {
        Map<String, Object> address = new HashMap<>();
        address.put("receiverName", "测试");
        address.put("receiverPhone", user.getPhone());
        address.put("province", "广东省");
        address.put("city", "深圳市");
        address.put("district", "南山区");
        address.put("detailAddress", "测试路 1 号");
        address.put("isDefault", 0);
        assertOk(post("/address", address, user));
        ResponseEntity<Map> list = get("/address/list", user);
        List<Map<String, Object>> records = (List<Map<String, Object>>) list.getBody().get("data");
        assertThat(records).isNotEmpty();
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    private Map<String, Object> taskBody(Long addressId) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "边界任务-" + System.currentTimeMillis());
        body.put("description", "边界测试描述");
        body.put("reward", 5);
        body.put("categoryId", 1);
        body.put("addressId", addressId);
        body.put("deadline", "2026-12-31 18:00:00");
        return body;
    }

    private Long publishAndGetId(AuthUser user, Long addressId) {
        assertOk(postWithSubmitToken("/task", taskBody(addressId), user, "publishTask"));
        ResponseEntity<Map> myList = get("/task/my-published?pageNum=1&pageSize=1", user);
        List<Map<String, Object>> records = (List<Map<String, Object>>)
                ((Map<?, ?>) myList.getBody().get("data")).get("records");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    private Long myLatestApplicationId(AuthUser user, Long taskId) {
        ResponseEntity<Map> resp = get("/task/application/" + taskId + "?pageNum=1&pageSize=1", user);
        assertOk(resp);
        List<Map<String, Object>> records = (List<Map<String, Object>>)
                ((Map<?, ?>) resp.getBody().get("data")).get("records");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    /** 订单接口无"按任务查订单"端点，走 Service 查库拿 taskId 对应订单 */
    private Long taskOrderId(Long taskId) {
        TaskOrder order = taskOrderService.lambdaQuery()
                .eq(TaskOrder::getTaskId, taskId).one();
        assertThat(order).isNotNull();
        return order.getId();
    }
}
