package com.plumelog.server.monitor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.dingtalk.api.DefaultDingTalkClient;
import com.dingtalk.api.DingTalkClient;
import com.dingtalk.api.request.OapiRobotSendRequest;
import com.dingtalk.api.response.OapiRobotSendResponse;
import com.plumelog.core.constant.LogMessageConstant;
import com.plumelog.core.dto.RunLogMessage;
import com.plumelog.core.dto.WarningRule;
import com.plumelog.core.redis.RedisClient;
import com.plumelog.server.client.ElasticLowerClient;
import com.taobao.api.ApiException;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * className：PlumeLogMonitorListener
 * description： 日志监控报警
 * time：2020-07-02.11:19
 *
 * @author Tank
 * @version 1.0.0
 */
@Component
public class PlumeLogMonitorListener implements ApplicationListener<PlumelogMonitorEvent> {

    private static org.slf4j.Logger logger = LoggerFactory.getLogger(PlumeLogMonitorListener.class);

    @Autowired
    private PlumeLogMonitorRuleConfig plumeLogMonitorRuleConfig;

    @Autowired
    private RedisClient redisClient;

    private static final String WARNING_NOTICE = ":WARNING:NOTICE";

    @Value("${plumelog.ui.url:127.0.0.1:8989}")
    private String url;

    /**
     * 当KEY设置过期时间时加的后缀
     */
    private static final String KEY_NX = ":NX";

    @Autowired
    private ElasticLowerClient elasticLowerClient;


    @Async
    @Override
    public void onApplicationEvent(PlumelogMonitorEvent event) {
        List<String> logs = event.getLogs();
        List<RunLogMessage> runlogs = new ArrayList<>();
        logs.forEach(logString -> {
            runlogs.add(JSON.parseObject(logString, RunLogMessage.class));
        });
        //解析日志
        parserLogMessage(runlogs);
    }

    /**
     * 解析日志
     *
     * @param logMessages 日志
     */
    public void parserLogMessage(List<RunLogMessage> logMessages) {
        logMessages.forEach(runLogMessage -> {
            List<WarningRule> monitorRuleConfig = plumeLogMonitorRuleConfig.getMonitorRuleConfig(runLogMessage.getAppName());
            if (monitorRuleConfig != null) {
                //运行规则
                enforcementRules(monitorRuleConfig, runLogMessage);
            }
        });

    }

    /**
     * 执行日志监控规则
     *
     * @param rules         规则
     * @param runLogMessage 日志
     */
    public void enforcementRules(List<WarningRule> rules, RunLogMessage runLogMessage) {
        for (int i = 0; i < rules.size(); i++) {
            WarningRule warningRule = rules.get(i);
            String className = warningRule.getClassName();
            String appName = warningRule.getAppName();
            if (!StringUtils.isEmpty(className)
                    && !className.equals(runLogMessage.getClassName())) {
                continue;
            }
            //统计分析
            statisticAlnalysis(getKey(appName, className), warningRule);
        }
    }


    /**
     * 统计分析
     *
     * @param key  缓存key
     * @param rule 规则
     */
    private void statisticAlnalysis(String key, WarningRule rule) {
        String time = redisClient.hget(key, LogMessageConstant.PLUMELOG_MONITOR_KEY_MAP_FILED_TIME);
        if (StringUtils.isEmpty(time)) {
            redisClient.hset(key, LogMessageConstant.PLUMELOG_MONITOR_KEY_MAP_FILED_TIME, String.valueOf(System.currentTimeMillis()));
        } else {
            long startTime = Long.parseLong(time);
            long endTime = startTime + (rule.getTime() * 1000);
            if (endTime > System.currentTimeMillis()) {
                Long incr = redisClient.hincrby(key, LogMessageConstant.PLUMELOG_MONITOR_KEY_MAP_FILED_COUNT, 1);
                if (incr > rule.getErrorCount() && !redisClient.existsKey(key + WARNING_NOTICE)) {
                    earlyWarning(rule, incr, key);
                    redisClient.del(key);
                }
            } else {
                redisClient.hdel(key, LogMessageConstant.PLUMELOG_MONITOR_KEY_MAP_FILED_TIME,
                        LogMessageConstant.PLUMELOG_MONITOR_KEY_MAP_FILED_COUNT);
                redisClient.hincrby(key, LogMessageConstant.PLUMELOG_MONITOR_KEY_MAP_FILED_COUNT, 1);
                redisClient.hset(key, LogMessageConstant.PLUMELOG_MONITOR_KEY_MAP_FILED_TIME,
                        String.valueOf(System.currentTimeMillis()));
            }
        }

    }

    /**
     * 组装key
     *
     * @param appName   应用名
     * @param className 类名
     * @return
     */
    private static String getKey(String appName, String className) {
        String key = LogMessageConstant.PLUMELOG_MONITOR_KEY + appName;
        if (!StringUtils.isEmpty(className)) {
            key = key + ":" + className;
        }
        return key;
    }

    /**
     * 执行预警
     *
     * @param rule
     * @param count
     * @param key
     */
    private void earlyWarning(WarningRule rule, long count, String key) {
        PlumeLogMonitorTextMessage plumeLogMonitorTextMessage =
                new PlumeLogMonitorTextMessage.Builder(rule.getAppName())
                        .className(rule.getClassName())
                        .errorCount(rule.getErrorCount())
                        .time(rule.getTime())
                        .count(count)
                        .monitorUrl(getMonitorMessageURL(rule))
                        .build();
        if (!StringUtils.isEmpty(rule.getReceiver())) {
            plumeLogMonitorTextMessage.setAtMobiles(Arrays.asList(rule.getReceiver().split(",")));
        }
        DingTalkClient client = new DefaultDingTalkClient(rule.getWebhookUrl());
        OapiRobotSendRequest request = new OapiRobotSendRequest();
        request.setMsgtype("markdown");
        OapiRobotSendRequest.Markdown markdown = new OapiRobotSendRequest.Markdown();
        markdown.setTitle("报警通知");
        markdown.setText(plumeLogMonitorTextMessage.getText());
        request.setMarkdown(markdown);
        OapiRobotSendRequest.At at = new OapiRobotSendRequest.At();
        at.setAtMobiles(plumeLogMonitorTextMessage.getAtMobiles());
        at.setIsAtAll(plumeLogMonitorTextMessage.isAtAll());
        request.setAt(at);
        OapiRobotSendResponse response = null;
        try {
            String warningKey = key + WARNING_NOTICE;
            if (redisClient.setNx(warningKey + KEY_NX, 5)) {
                logger.info(plumeLogMonitorTextMessage.getText());
                response = client.execute(request);
                sendMesageES(rule, count);
            }
            redisClient.set(warningKey, warningKey);
            redisClient.expireAt(warningKey, Long.parseLong(String.valueOf(rule.getTime())));
        } catch (ApiException e) {
            e.printStackTrace();
            logger.error(response.getErrmsg());
        }
    }

    /**
     * 报警记录加入至ES
     */
    private void sendMesageES(WarningRule rule, long count) {
        try {
            JSONObject object = (JSONObject) JSONObject.toJSON(rule);
            object.put("count", count);
            object.put("dataTime", System.currentTimeMillis());
            elasticLowerClient.insertList(Arrays.asList(object.toJSONString()),
                    LogMessageConstant.PLUMELOG_MONITOR_MESSAGE_KEY,
                    LogMessageConstant.ES_TYPE);
            logger.info("monitor message insert es success");
        } catch (IOException e) {
            e.printStackTrace();
            logger.error("monitor message insert es failed!", e);
        }
    }

    private String getMonitorMessageURL(WarningRule rule) {
        //换算毫秒数
        int time = rule.getTime() * 1000;
        long currentTime = System.currentTimeMillis();
        //开始时间
        long startTime = currentTime - time;
        StringBuilder builder = new StringBuilder(64);
        builder.append(url).append("?appName=").append(rule.getAppName())
                .append("&className=").append(rule.getClassName())
                .append("&logLevel=ERROR")
                .append("&time=").append(startTime).append(",").append(currentTime);
        return builder.toString();
    }
}
