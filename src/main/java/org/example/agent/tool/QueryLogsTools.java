package org.example.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencentcloudapi.cls.v20201016.ClsClient;
import com.tencentcloudapi.cls.v20201016.models.SearchLogRequest;
import com.tencentcloudapi.cls.v20201016.models.SearchLogResponse;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import lombok.Data;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 日志查询工具
 * 以腾讯云 CLS 作为统一日志中心，支持基础日志查询、按告警联动查询、按 trace 查询。
 * 工具内部会对原始日志进行预处理，返回统计、异常摘要和证据样本，减少 LLM 噪音。
 */
@Component
public class QueryLogsTools {

    private static final Logger logger = LoggerFactory.getLogger(QueryLogsTools.class);
    
    public static final String TOOL_QUERY_LOGS = "queryLogs";
    public static final String TOOL_QUERY_LOGS_BY_ALERT = "queryLogsByAlert";
    public static final String TOOL_QUERY_LOGS_BY_TRACE_ID = "queryLogsByTraceId";
    public static final String TOOL_GET_AVAILABLE_LOG_TOPICS = "getAvailableLogTopics";

    private static final String DEFAULT_REGION = "ap-guangzhou";
    private static final String DEFAULT_ENV = "prod";
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_EVIDENCE_SAMPLES = 8;
    private static final int DEFAULT_ALERT_LOOKBACK_MINUTES = 15;
    private static final int DEFAULT_TRACE_LOOKBACK_MINUTES = 60;

    private static final List<String> VALID_REGIONS = List.of(
            "ap-guangzhou", "ap-shanghai", "ap-beijing", "ap-chengdu"
    );

    private static final DateTimeFormatter OUTPUT_TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private static final DateTimeFormatter INPUT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\b");
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Value("${cls.mock-enabled:false}")
    private boolean mockEnabled;
    
    @Value("${cls.timeout:10}")
    private int timeout;

    @Value("${cls.topic-id:}")
    private String defaultTopicId;

    @Value("${cls.topic-name:application-logs}")
    private String defaultTopicName;

    @Value("${cls.logset-id:}")
    private String logsetId;

    @Value("${cls.secret-id:}")
    private String secretId;

    @Value("${cls.secret-key:}")
    private String secretKey;

    @Value("${cls.env:prod}")
    private String defaultEnv;

    @Value("${cls.service-field:service}")
    private String serviceField;

    @Value("${cls.level-field:level}")
    private String levelField;

    @Value("${cls.message-field:message}")
    private String messageField;

    @Value("${cls.trace-id-field:traceId}")
    private String traceIdField;

    @Value("${cls.uri-field:uri}")
    private String uriField;

    @Value("${cls.http-status-field:httpStatus}")
    private String httpStatusField;

    @Value("${cls.cost-ms-field:costMs}")
    private String costMsField;

    @Value("${cls.exception-field:exception}")
    private String exceptionField;

    @Value("${cls.host-field:host}")
    private String hostField;

    @Value("${cls.pod-field:pod}")
    private String podField;

    @Value("${cls.logger-field:logger}")
    private String loggerField;

    private OkHttpClient httpClient;

    @jakarta.annotation.PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(timeout))
                .readTimeout(Duration.ofSeconds(timeout))
                .build();
        logger.info("✅ QueryLogsTools 初始化成功, mock={}, defaultTopicName={}, logsetIdConfigured={}, topicIdConfigured={}",
                mockEnabled, defaultTopicName, !isBlank(logsetId), !isBlank(defaultTopicId));
    }

    @Tool(description = "Get available CLS log topics and query capabilities. " +
            "This tool returns the default topic, topic examples, supported regions, and standard log fields. " +
            "Use it before querying logs if you need to understand what fields and topics are available.")
    public String getAvailableLogTopics() {
        try {
            List<LogTopicInfo> topics = buildTopicInfos();
            LogTopicsOutput output = new LogTopicsOutput();
            output.setSuccess(true);
            output.setTopics(topics);
            output.setAvailableRegions(VALID_REGIONS);
            output.setDefaultRegion(DEFAULT_REGION);
            output.setDefaultTopicId(defaultTopicId);
            output.setDefaultTopicName(defaultTopicName);
            output.setStandardFields(List.of(
                    "timestamp", "level", "service", "env", "traceId", "host", "pod", "logger",
                    "message", "exception", "uri", "httpStatus", "costMs"
            ));
            output.setMessage("CLS 日志中心已启用。建议优先使用 queryLogsByAlert 或 queryLogsByTraceId 进行场景化查询。");
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            logger.error("获取日志主题列表失败", e);
            return buildErrorResponse("获取日志主题列表失败: " + e.getMessage());
        }
    }

    @Tool(description = "Query logs from Tencent Cloud CLS. " +
            "Use this for direct structured log retrieval. " +
            "Parameters: region, topicId, query, startTime, endTime, limit. " +
            "The tool returns aggregated statistics, top exceptions, message patterns, summary, and evidence samples instead of only raw logs.")
    public String queryLogs(
            @ToolParam(description = "地域，可选值: ap-guangzhou, ap-shanghai, ap-beijing, ap-chengdu；为空默认 ap-guangzhou") String region,
            @ToolParam(description = "CLS TopicId；为空时使用配置中的默认 topicId。若仅知道 TopicName，请先在系统配置中绑定映射") String topicId,
            @ToolParam(description = "CLS 检索语句，建议使用结构化字段，例如 level:ERROR AND service:payment-service") String query,
            @ToolParam(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss；为空默认当前时间前15分钟") String startTime,
            @ToolParam(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss；为空默认当前时间") String endTime,
            @ToolParam(description = "返回日志条数，默认20，最大100") Integer limit) {
        try {
            QueryContext context = buildBaseQueryContext(region, topicId, query, startTime, endTime, limit);
            List<LogEntry> logEntries = fetchLogs(context);
            QueryLogsOutput output = buildQueryOutput(context, logEntries, "基础日志查询完成");
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            logger.error("基础日志查询失败", e);
            return buildErrorResponse("基础日志查询失败: " + e.getMessage());
        }
    }

    @Tool(description = "Query logs by alert type. " +
            "This tool is designed for AIOps workflows. " +
            "Input alertName, service, time range, region, and limit. " +
            "The tool automatically expands the query window and builds the best CLS query template for alerts such as HighCPUUsage, HighMemoryUsage, HighDiskUsage, SlowResponse, and ServiceUnavailable.")
    public String queryLogsByAlert(
            @ToolParam(description = "告警名称，例如 HighCPUUsage、HighMemoryUsage、HighDiskUsage、SlowResponse、ServiceUnavailable") String alertName,
            @ToolParam(description = "受影响服务名，例如 payment-service；若为空则由工具根据告警类型使用默认服务") String service,
            @ToolParam(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss；为空时默认回看 15 分钟") String startTime,
            @ToolParam(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss；为空时默认当前时间") String endTime,
            @ToolParam(description = "地域，默认 ap-guangzhou") String region,
            @ToolParam(description = "返回日志条数，默认20，最大100") Integer limit) {
        try {
            AlertQueryTemplate template = buildAlertTemplate(alertName, service, startTime, endTime, region, limit);
            QueryContext context = buildBaseQueryContext(
                    template.getRegion(),
                    template.getTopicId(),
                    template.getQuery(),
                    template.getStartTime(),
                    template.getEndTime(),
                    template.getLimit()
            );
            List<LogEntry> logEntries = fetchLogs(context);
            QueryLogsOutput output = buildQueryOutput(context, logEntries, "按告警联动日志查询完成");
            output.setAlertName(template.getAlertName());
            output.setTargetService(template.getService());
            output.setQueryTemplate(template.getTemplateName());
            output.setSuggestedNextAction(template.getSuggestedNextAction());
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            logger.error("按告警查询日志失败", e);
            return buildErrorResponse("按告警查询日志失败: " + e.getMessage());
        }
    }

    @Tool(description = "Query logs by traceId for a single request chain. " +
            "Use this tool when you need to trace a failed request across one service or multiple related logs. " +
            "The tool returns the chronological evidence chain and related exception statistics.")
    public String queryLogsByTraceId(
            @ToolParam(description = "请求链路 traceId") String traceId,
            @ToolParam(description = "服务名，例如 payment-service；可为空") String service,
            @ToolParam(description = "开始时间，格式 yyyy-MM-dd HH:mm:ss；为空默认当前时间前60分钟") String startTime,
            @ToolParam(description = "结束时间，格式 yyyy-MM-dd HH:mm:ss；为空默认当前时间") String endTime,
            @ToolParam(description = "地域，默认 ap-guangzhou") String region,
            @ToolParam(description = "返回日志条数，默认20，最大100") Integer limit) {
        try {
            if (isBlank(traceId)) {
                return buildErrorResponse("traceId 不能为空");
            }

            StringBuilder queryBuilder = new StringBuilder();
            queryBuilder.append(traceIdField).append(":\"").append(escapeQueryValue(traceId)).append("\"");
            if (!isBlank(service)) {
                queryBuilder.append(" AND ").append(serviceField).append(":\"")
                        .append(escapeQueryValue(service)).append("\"");
            }

            QueryContext context = buildBaseQueryContext(
                    region,
                    defaultTopicId,
                    queryBuilder.toString(),
                    startTime,
                    endTime,
                    limit == null ? DEFAULT_LIMIT : limit
            );
            if (isBlank(context.getStartTimeRaw())) {
                context.setStartTimeRaw(formatTime(Instant.now().minus(DEFAULT_TRACE_LOOKBACK_MINUTES, ChronoUnit.MINUTES)));
            }

            List<LogEntry> logEntries = fetchLogs(context);
            QueryLogsOutput output = buildQueryOutput(context, logEntries, "按 traceId 查询日志完成");
            output.setTargetService(service);
            output.setTraceId(traceId);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            logger.error("按 traceId 查询日志失败", e);
            return buildErrorResponse("按 traceId 查询日志失败: " + e.getMessage());
        }
    }

    private List<LogEntry> fetchLogs(QueryContext context) throws Exception {
            List<LogEntry> logEntries;
            if (mockEnabled) {
            logEntries = buildMockLogs(context);
            logger.info("使用 Mock 数据完成日志查询，query={}, matched={}", context.getQuery(), logEntries.size());
            } else {
            logEntries = queryLogsFromCls(context);
            logger.info("CLS 真实日志查询完成，query={}, matched={}", context.getQuery(), logEntries.size());
        }
        logEntries.sort(Comparator.comparing(LogEntry::getTimestamp, Comparator.nullsLast(String::compareTo)).reversed());
        return logEntries;
    }

    private QueryContext buildBaseQueryContext(String region, String topicId, String query, String startTime, String endTime, Integer limit) {
        QueryContext context = new QueryContext();
        context.setRegion(normalizeRegion(region));
        context.setTopicId(resolveTopicId(topicId));
        context.setTopicName(defaultTopicName);
        context.setLimit(normalizeLimit(limit));
        context.setQuery(defaultIfBlank(query, buildDefaultQuery()));
        context.setStartTimeRaw(startTime);
        context.setEndTimeRaw(endTime);
        context.setStartTimeEpoch(resolveStartEpochSeconds(startTime, DEFAULT_ALERT_LOOKBACK_MINUTES));
        context.setEndTimeEpoch(resolveEndEpochSeconds(endTime));
        return context;
    }

    private QueryLogsOutput buildQueryOutput(QueryContext context, List<LogEntry> logEntries, String message) {
            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(!logEntries.isEmpty());
        output.setRegion(context.getRegion());
        output.setTopicId(context.getTopicId());
        output.setTopicName(context.getTopicName());
        output.setQuery(context.getQuery());
        output.setStartTime(formatTime(Instant.ofEpochSecond(context.getStartTimeEpoch())));
        output.setEndTime(formatTime(Instant.ofEpochSecond(context.getEndTimeEpoch())));
            output.setTotal(logEntries.size());

        LogStatistics statistics = buildStatistics(logEntries);
        output.setStatistics(statistics);
        output.setSummary(buildSummary(logEntries, statistics, context));
        output.setEvidenceSamples(logEntries.stream().limit(MAX_EVIDENCE_SAMPLES).collect(Collectors.toList()));
        output.setLogs(logEntries.stream().limit(Math.min(context.getLimit(), MAX_EVIDENCE_SAMPLES)).collect(Collectors.toList()));
        output.setMessage(logEntries.isEmpty() ? "未找到匹配的日志" : message);
        return output;
    }

    private List<LogEntry> queryLogsFromCls(QueryContext context) throws TencentCloudSDKException {
        if (isBlank(secretId) || isBlank(secretKey)) {
            throw new IllegalStateException("CLS 凭证未配置，请设置 cls.secret-id 和 cls.secret-key");
        }
        if (isBlank(context.getTopicId())) {
            throw new IllegalStateException("CLS TopicId 未配置，请设置 cls.topic-id 或在调用参数中传入 topicId");
        }

        Credential credential = new Credential(secretId, secretKey);
        HttpProfile httpProfile = new HttpProfile();
        httpProfile.setEndpoint("cls.tencentcloudapi.com");
        HttpProfile cloned = httpProfile;
        ClientProfile clientProfile = new ClientProfile();
        clientProfile.setHttpProfile(cloned);

        ClsClient client = new ClsClient(credential, context.getRegion(), clientProfile);
        SearchLogRequest request = new SearchLogRequest();
        request.setTopicId(context.getTopicId());
        request.setFrom(context.getStartTimeEpoch());
        request.setTo(context.getEndTimeEpoch());
        request.setQuery(context.getQuery());
        request.setLimit((long) context.getLimit());
        request.setSort("desc");
        request.setUseNewAnalysis(true);

        SearchLogResponse response = client.SearchLog(request);
        return parseClsResponse(response);
    }

    private List<LogEntry> parseClsResponse(SearchLogResponse response) {
        if (response == null || response.getResults() == null || response.getResults().length == 0) {
            return Collections.emptyList();
        }

        List<LogEntry> entries = new ArrayList<>();
        Arrays.stream(response.getResults()).forEach(result -> {
            LogEntry entry = new LogEntry();
            Map<String, String> rawFields = new LinkedHashMap<>();

            if (result.getLogInfos() != null) {
                Arrays.stream(result.getLogInfos()).forEach(logInfo -> {
                    if (logInfo.getKey() != null && logInfo.getValue() != null) {
                        rawFields.put(logInfo.getKey(), logInfo.getValue());
                    }
                });
            }

            String timestamp = rawFields.getOrDefault("timestamp", rawFields.getOrDefault("__TIMESTAMP__", null));
            entry.setTimestamp(normalizeClsTimestamp(timestamp, result.getTime()));
            entry.setLevel(rawFields.getOrDefault(levelField, rawFields.getOrDefault("level", "INFO")));
            entry.setService(rawFields.getOrDefault(serviceField, rawFields.getOrDefault("service", "unknown-service")));
            entry.setEnv(rawFields.getOrDefault("env", defaultIfBlank(defaultEnv, DEFAULT_ENV)));
            entry.setTraceId(rawFields.getOrDefault(traceIdField, rawFields.get("traceId")));
            entry.setHost(rawFields.getOrDefault(hostField, rawFields.get("host")));
            entry.setPod(rawFields.getOrDefault(podField, rawFields.get("pod")));
            entry.setLogger(rawFields.getOrDefault(loggerField, rawFields.get("logger")));
            entry.setMessage(rawFields.getOrDefault(messageField, rawFields.getOrDefault("message", result.getPkgLogId())));
            entry.setException(rawFields.getOrDefault(exceptionField, rawFields.get("exception")));
            entry.setUri(rawFields.getOrDefault(uriField, rawFields.get("uri")));
            entry.setHttpStatus(rawFields.getOrDefault(httpStatusField, rawFields.get("httpStatus")));
            entry.setCostMs(rawFields.getOrDefault(costMsField, rawFields.get("costMs")));
            entry.setRawFields(rawFields);
            entries.add(entry);
        });
        return entries;
    }

    private AlertQueryTemplate buildAlertTemplate(String alertName, String service, String startTime, String endTime, String region, Integer limit) {
        String normalizedAlert = defaultIfBlank(alertName, "GenericAlert").trim();
        String resolvedService = defaultIfBlank(service, inferDefaultServiceByAlert(normalizedAlert));
        String normalizedRegion = normalizeRegion(region);
        String resolvedStartTime = defaultIfBlank(startTime, formatTime(Instant.now().minus(DEFAULT_ALERT_LOOKBACK_MINUTES, ChronoUnit.MINUTES)));
        String resolvedEndTime = defaultIfBlank(endTime, formatTime(Instant.now()));

        AlertQueryTemplate template = new AlertQueryTemplate();
        template.setAlertName(normalizedAlert);
        template.setService(resolvedService);
        template.setRegion(normalizedRegion);
        template.setTopicId(defaultTopicId);
        template.setStartTime(resolvedStartTime);
        template.setEndTime(resolvedEndTime);
        template.setLimit(normalizeLimit(limit));

        switch (normalizedAlert) {
            case "HighCPUUsage" -> {
                template.setTemplateName("cpu_hotspot_template");
                template.setQuery(buildCpuAlertQuery(resolvedService));
                template.setSuggestedNextAction("建议继续检查高 CPU 时间窗内的线程池、下游超时和异常堆栈。");
            }
            case "HighMemoryUsage" -> {
                template.setTemplateName("memory_pressure_template");
                template.setQuery(buildMemoryAlertQuery(resolvedService));
                template.setSuggestedNextAction("建议继续检查 OOM、Full GC、堆内存增长和对象堆积迹象。");
            }
            case "HighDiskUsage" -> {
                template.setTemplateName("disk_pressure_template");
                template.setQuery(buildDiskAlertQuery(resolvedService));
                template.setSuggestedNextAction("建议继续检查磁盘写满、日志暴涨和临时文件堆积问题。");
            }
            case "SlowResponse" -> {
                template.setTemplateName("slow_response_template");
                template.setQuery(buildSlowResponseAlertQuery(resolvedService));
                template.setSuggestedNextAction("建议继续检查慢 SQL、缓存未命中、下游依赖超时和热点接口。");
            }
            case "ServiceUnavailable" -> {
                template.setTemplateName("service_unavailable_template");
                template.setQuery(buildServiceUnavailableAlertQuery(resolvedService));
                template.setSuggestedNextAction("建议继续检查 5xx、重启、连接拒绝、依赖不可达和容器异常退出。");
            }
            default -> {
                template.setTemplateName("generic_alert_template");
                template.setQuery(buildGenericAlertQuery(resolvedService, normalizedAlert));
                template.setSuggestedNextAction("建议结合内部文档继续确认该告警的标准排查步骤。");
            }
        }
        return template;
    }

    private String buildCpuAlertQuery(String service) {
        return joinConditions(
                fieldEquals(serviceField, service),
                "(" + levelField + ":ERROR OR " + levelField + ":WARN OR " + messageField + ":\"timeout\" OR " + messageField + ":\"thread\" OR " + messageField + ":\"cpu\")"
        );
    }

    private String buildMemoryAlertQuery(String service) {
        return joinConditions(
                fieldEquals(serviceField, service),
                "(" + levelField + ":ERROR OR " + levelField + ":WARN OR " + messageField + ":\"oom\" OR " + exceptionField + ":\"OutOfMemoryError\" OR " + messageField + ":\"full gc\")"
        );
    }

    private String buildDiskAlertQuery(String service) {
        return joinConditions(
                fieldEquals(serviceField, service),
                "(" + levelField + ":WARN OR " + messageField + ":\"disk\" OR " + messageField + ":\"no space\" OR " + messageField + ":\"filesystem\")"
        );
    }

    private String buildSlowResponseAlertQuery(String service) {
        return joinConditions(
                fieldEquals(serviceField, service),
                "(" + levelField + ":ERROR OR " + levelField + ":WARN OR " + messageField + ":\"slow\" OR " + messageField + ":\"timeout\" OR " + costMsField + ":[3000 TO *] OR " + httpStatusField + ":[500 TO 599])"
        );
    }

    private String buildServiceUnavailableAlertQuery(String service) {
        return joinConditions(
                fieldEquals(serviceField, service),
                "(" + levelField + ":ERROR OR " + levelField + ":FATAL OR " + httpStatusField + ":[500 TO 599] OR " + messageField + ":\"refused\" OR " + messageField + ":\"unavailable\" OR " + messageField + ":\"crash\")"
        );
    }

    private String buildGenericAlertQuery(String service, String alertName) {
        return joinConditions(
                fieldEquals(serviceField, service),
                "(" + levelField + ":ERROR OR " + levelField + ":WARN OR " + messageField + ":\"" + escapeQueryValue(alertName) + "\")"
        );
    }

    private String buildDefaultQuery() {
        return joinConditions(
                fieldEquals("env", defaultIfBlank(defaultEnv, DEFAULT_ENV)),
                "(" + levelField + ":ERROR OR " + levelField + ":WARN)"
        );
    }

    private String inferDefaultServiceByAlert(String alertName) {
        return switch (alertName) {
            case "HighCPUUsage" -> "payment-service";
            case "HighMemoryUsage" -> "order-service";
            case "SlowResponse" -> "user-service";
            case "HighDiskUsage" -> "log-collector";
            case "ServiceUnavailable" -> "order-service";
            default -> "generic-service";
        };
    }

    private List<LogEntry> buildMockLogs(QueryContext context) {
        Instant now = Instant.now();
        String normalizedQuery = context.getQuery() == null ? "" : context.getQuery().toLowerCase(Locale.ROOT);
        String service = inferServiceFromQuery(normalizedQuery, context);

        if (normalizedQuery.contains("traceid")) {
            return buildTraceLogs(now, service, context.getQuery(), context.getLimit());
        }
        if (normalizedQuery.contains("cpu")) {
            return buildCpuLogs(now, service, context.getLimit());
        }
        if (normalizedQuery.contains("oom") || normalizedQuery.contains("full gc") || normalizedQuery.contains("memory")) {
            return buildMemoryLogs(now, service, context.getLimit());
        }
        if (normalizedQuery.contains("slow") || normalizedQuery.contains("timeout") || normalizedQuery.contains("500") || normalizedQuery.contains("costms")) {
            return buildSlowResponseLogs(now, service, context.getLimit());
        }
        if (normalizedQuery.contains("disk") || normalizedQuery.contains("filesystem") || normalizedQuery.contains("no space")) {
            return buildDiskLogs(now, service, context.getLimit());
        }
        if (normalizedQuery.contains("crash") || normalizedQuery.contains("refused") || normalizedQuery.contains("unavailable")) {
            return buildServiceUnavailableLogs(now, service, context.getLimit());
        }
        return buildGenericLogs(now, service, context.getQuery(), context.getLimit());
    }

    private List<LogEntry> buildTraceLogs(Instant now, String service, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        String traceId = extractTraceId(query);
        for (int i = 0; i < Math.min(limit, 6); i++) {
            LogEntry log = baseLog(now.minus(i * 20L, ChronoUnit.SECONDS), i == 0 ? "ERROR" : "INFO", service,
                    "trace request step " + (i + 1) + " for traceId=" + traceId);
            log.setTraceId(traceId);
            log.setUri("/api/pay/submit");
            log.setHttpStatus(i == 0 ? "500" : "200");
            log.setCostMs(String.valueOf(800 + i * 120));
            if (i == 0) {
                log.setException("java.net.SocketTimeoutException");
                log.setMessage("downstream timeout when calling inventory-service, traceId=" + traceId);
            }
                logs.add(log);
        }
        return logs;
    }

    private List<LogEntry> buildCpuLogs(Instant now, String service, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, 6); i++) {
            LogEntry log = baseLog(now.minus(i * 2L, ChronoUnit.MINUTES), i < 2 ? "ERROR" : "WARN", service,
                    String.format("cpu hotspot detected, thread pool saturation, cpu=%d%%", 94 - i));
            log.setException(i == 0 ? "java.util.concurrent.TimeoutException" : null);
            log.setCostMs(String.valueOf(1800 + i * 100));
            log.getRawFields().put("cpuUsage", String.valueOf(94 - i));
            logs.add(log);
        }
        return logs;
    }

    private List<LogEntry> buildMemoryLogs(Instant now, String service, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, 6); i++) {
            LogEntry log = baseLog(now.minus(i * 3L, ChronoUnit.MINUTES), i < 2 ? "ERROR" : "WARN", service,
                    i == 0 ? "java.lang.OutOfMemoryError: Java heap space" : "full gc frequency increased, old gen usage high");
            log.setException(i == 0 ? "java.lang.OutOfMemoryError" : null);
            log.getRawFields().put("heapUsage", (91 - i) + "%");
            logs.add(log);
        }
        return logs;
    }

    private List<LogEntry> buildSlowResponseLogs(Instant now, String service, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, 8); i++) {
            LogEntry log = baseLog(now.minus(i, ChronoUnit.MINUTES), i < 3 ? "ERROR" : "WARN", service,
                    i % 2 == 0 ? "slow request detected for /api/v1/users/profile" : "downstream timeout when calling mysql");
            log.setUri(i % 2 == 0 ? "/api/v1/users/profile" : "/api/v1/users/orders");
            log.setHttpStatus(i < 3 ? "500" : "200");
            log.setCostMs(String.valueOf(4200 - i * 150));
            if (i % 2 == 1) {
                log.setException("QueryTimeoutException");
            }
                logs.add(log);
            }
        return logs;
    }

    private List<LogEntry> buildDiskLogs(Instant now, String service, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, 5); i++) {
            LogEntry log = baseLog(now.minus(i * 4L, ChronoUnit.MINUTES), "WARN", service,
                    String.format("filesystem usage high on /data, usage=%d%%, no space risk", 88 + i));
            log.getRawFields().put("diskUsage", (88 + i) + "%");
            logs.add(log);
        }
        return logs;
    }
    
    private List<LogEntry> buildServiceUnavailableLogs(Instant now, String service, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, 6); i++) {
            LogEntry log = baseLog(now.minus(i * 90L, ChronoUnit.SECONDS), i < 3 ? "ERROR" : "WARN", service,
                    i == 0 ? "service unavailable, connection refused from downstream gateway" : "pod restart detected after fatal error");
            log.setHttpStatus(i < 2 ? "503" : "500");
            if (i == 0) {
                log.setException("ConnectException");
            }
            logs.add(log);
        }
        return logs;
    }

    private List<LogEntry> buildGenericLogs(Instant now, String service, String query, int limit) {
        List<LogEntry> logs = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, 5); i++) {
            LogEntry log = baseLog(now.minus(i, ChronoUnit.MINUTES), i % 2 == 0 ? "WARN" : "INFO", service,
                    "generic log sample, query=" + query);
            logs.add(log);
        }
        return logs;
    }

    private LogEntry baseLog(Instant timestamp, String level, String service, String message) {
        LogEntry log = new LogEntry();
        log.setTimestamp(formatTime(timestamp));
        log.setLevel(level);
        log.setService(defaultIfBlank(service, "generic-service"));
        log.setEnv(defaultIfBlank(defaultEnv, DEFAULT_ENV));
        log.setTraceId("trace-" + timestamp.getEpochSecond());
        log.setHost("10.0.1.12");
        log.setPod(defaultIfBlank(service, "generic-service") + "-pod-01");
        log.setLogger("org.example.mock.LogProducer");
        log.setMessage(message);
        log.setUri("/api/mock");
        log.setHttpStatus("200");
        log.setCostMs("120");
        log.setRawFields(new LinkedHashMap<>());
        return log;
    }

    private LogStatistics buildStatistics(List<LogEntry> logEntries) {
        LogStatistics statistics = new LogStatistics();
        statistics.setTotalMatched(logEntries.size());
        statistics.setLevelDistribution(countBy(logEntries, entry -> defaultIfBlank(entry.getLevel(), "UNKNOWN")));
        statistics.setTopExceptions(extractTopExceptions(logEntries));
        statistics.setTopMessagePatterns(extractTopMessagePatterns(logEntries));
        statistics.setTopServices(countBy(logEntries, entry -> defaultIfBlank(entry.getService(), "unknown-service")));
        statistics.setTopUris(countBy(logEntries, entry -> defaultIfBlank(entry.getUri(), "N/A")));
        return statistics;
    }

    private String buildSummary(List<LogEntry> logEntries, LogStatistics statistics, QueryContext context) {
        if (logEntries.isEmpty()) {
            return "在指定时间窗内未检索到相关日志，建议扩大时间范围或核对 service/topic 配置。";
        }

        LogEntry latest = logEntries.get(0);
        String topException = statistics.getTopExceptions().isEmpty() ? "无明显异常类型" : statistics.getTopExceptions().get(0).getName();
        String topPattern = statistics.getTopMessagePatterns().isEmpty() ? "无明显高频日志模式" : statistics.getTopMessagePatterns().get(0).getPattern();

        return String.format(
                "在 %s ~ %s 时间窗内共检索到 %d 条日志。最近一条日志来自服务 %s，级别为 %s。高频异常类型为 %s，高频日志模式为 %s。建议结合该时间窗继续核对下游依赖、资源使用和对应排障文档。",
                formatTime(Instant.ofEpochSecond(context.getStartTimeEpoch())),
                formatTime(Instant.ofEpochSecond(context.getEndTimeEpoch())),
                logEntries.size(),
                defaultIfBlank(latest.getService(), "unknown-service"),
                defaultIfBlank(latest.getLevel(), "UNKNOWN"),
                topException,
                topPattern
        );
    }

    private List<NameCount> extractTopExceptions(List<LogEntry> logEntries) {
        return countBy(logEntries, entry -> defaultIfBlank(entry.getException(), "N/A")).entrySet().stream()
                .filter(entry -> !"N/A".equals(entry.getKey()))
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(entry -> new NameCount(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    private List<MessagePatternCount> extractTopMessagePatterns(List<LogEntry> logEntries) {
        return countBy(logEntries, entry -> normalizeMessagePattern(entry.getMessage())).entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(entry -> new MessagePatternCount(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    private <T> Map<T, Integer> countBy(List<LogEntry> logEntries, java.util.function.Function<LogEntry, T> classifier) {
        Map<T, Integer> result = new LinkedHashMap<>();
        for (LogEntry logEntry : logEntries) {
            T key = classifier.apply(logEntry);
            result.put(key, result.getOrDefault(key, 0) + 1);
        }
        return result.entrySet().stream()
                .sorted(Map.Entry.<T, Integer>comparingByValue().reversed())
                .limit(5)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (a, b) -> a,
                        LinkedHashMap::new
                ));
    }

    private String normalizeMessagePattern(String message) {
        if (isBlank(message)) {
            return "N/A";
        }
        String normalized = NUMBER_PATTERN.matcher(message.toLowerCase(Locale.ROOT)).replaceAll("{num}");
        normalized = normalized.replaceAll("[a-f0-9]{8,}", "{id}");
        if (normalized.length() > 120) {
            return normalized.substring(0, 120) + "...";
        }
        return normalized;
    }

    private List<LogTopicInfo> buildTopicInfos() {
        List<LogTopicInfo> topics = new ArrayList<>();

        LogTopicInfo applicationTopic = new LogTopicInfo();
        applicationTopic.setTopicName(defaultTopicName);
        applicationTopic.setTopicId(defaultTopicId);
        applicationTopic.setDescription("应用结构化日志主题，字段包含 timestamp/level/service/env/traceId/host/pod/logger/message/exception/uri/httpStatus/costMs");
        applicationTopic.setExampleQueries(List.of(
                "level:ERROR AND service:payment-service",
                "service:user-service AND costMs:[3000 TO *]",
                "traceId:\"trace-123456\""
        ));
        applicationTopic.setRelatedAlerts(List.of("HighCPUUsage", "HighMemoryUsage", "SlowResponse", "ServiceUnavailable"));
        topics.add(applicationTopic);

        LogTopicInfo systemTopic = new LogTopicInfo();
        systemTopic.setTopicName("system-events");
        systemTopic.setTopicId(defaultTopicId);
        systemTopic.setDescription("系统和容器事件主题，可用于 Pod 重启、OOMKill、磁盘打满等系统级故障排查");
        systemTopic.setExampleQueries(List.of(
                "message:\"OOMKilled\"",
                "message:\"restart\"",
                "message:\"no space\""
        ));
        systemTopic.setRelatedAlerts(List.of("HighMemoryUsage", "HighDiskUsage", "ServiceUnavailable"));
        topics.add(systemTopic);

        return topics;
    }

    private String normalizeClsTimestamp(String timestamp, Long fallbackMillis) {
        if (!isBlank(timestamp)) {
            return timestamp;
        }
        if (fallbackMillis != null && fallbackMillis > 0) {
            return formatTime(Instant.ofEpochMilli(fallbackMillis));
        }
        return formatTime(Instant.now());
    }

    private long resolveStartEpochSeconds(String startTime, int defaultLookbackMinutes) {
        if (isBlank(startTime)) {
            return Instant.now().minus(defaultLookbackMinutes, ChronoUnit.MINUTES).getEpochSecond();
        }
        return parseToEpochSeconds(startTime);
    }

    private long resolveEndEpochSeconds(String endTime) {
        if (isBlank(endTime)) {
            return Instant.now().getEpochSecond();
        }
        return parseToEpochSeconds(endTime);
    }

    private long parseToEpochSeconds(String time) {
        try {
            if (time.contains("T")) {
                return Instant.parse(time).getEpochSecond();
            }
            LocalDateTime localDateTime = LocalDateTime.parse(time, INPUT_TIME_FORMATTER);
            return localDateTime.toEpochSecond(ZoneOffset.ofHours(8));
        } catch (Exception e) {
            throw new IllegalArgumentException("时间格式非法，请使用 yyyy-MM-dd HH:mm:ss 或 ISO-8601 格式: " + time);
        }
    }

    private String formatTime(Instant instant) {
        return OUTPUT_TIME_FORMATTER.format(instant);
    }

    private String normalizeRegion(String region) {
        String actualRegion = defaultIfBlank(region, DEFAULT_REGION);
        if (!VALID_REGIONS.contains(actualRegion)) {
            logger.warn("收到非法 region={}, 自动回退到默认地域 {}", actualRegion, DEFAULT_REGION);
            return DEFAULT_REGION;
        }
        return actualRegion;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String resolveTopicId(String topicId) {
        return defaultIfBlank(topicId, defaultTopicId);
    }

    private String inferServiceFromQuery(String query, QueryContext context) {
        if (query != null) {
            for (String candidate : List.of("payment-service", "order-service", "user-service", "log-collector")) {
                if (query.contains(candidate.toLowerCase(Locale.ROOT))) {
                    return candidate;
                }
            }
        }
        return defaultIfBlank(context.getTopicName(), "generic-service").contains("application") ? "generic-service" : "generic-service";
    }

    private String extractTraceId(String query) {
        if (isBlank(query)) {
            return "trace-unknown";
        }
        int idx = query.toLowerCase(Locale.ROOT).indexOf("traceid");
        if (idx < 0) {
            return "trace-unknown";
        }
        return query.substring(idx).replaceAll(".*?\"(.*?)\".*", "$1");
    }

    private String joinConditions(String... conditions) {
        return Arrays.stream(conditions)
                .filter(condition -> !isBlank(condition))
                .collect(Collectors.joining(" AND "));
    }

    private String fieldEquals(String field, String value) {
        if (isBlank(value)) {
            return "";
        }
        return field + ":\"" + escapeQueryValue(value) + "\"";
    }

    private String escapeQueryValue(String value) {
        return value.replace("\"", "\\\"");
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String buildErrorResponse(String message) {
        try {
            QueryLogsOutput output = new QueryLogsOutput();
            output.setSuccess(false);
            output.setMessage(message);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(output);
        } catch (Exception e) {
            return String.format("{\"success\":false,\"message\":\"%s\"}", message);
        }
    }
    
    @Data
    private static class QueryContext {
        private String region;
        private String topicId;
        private String topicName;
        private String query;
        private int limit;
        private long startTimeEpoch;
        private long endTimeEpoch;
        private String startTimeRaw;
        private String endTimeRaw;
    }

    @Data
    private static class AlertQueryTemplate {
        private String alertName;
        private String service;
        private String region;
        private String topicId;
        private String startTime;
        private String endTime;
        private int limit;
        private String query;
        private String templateName;
        private String suggestedNextAction;
    }

    @Data
    public static class LogEntry {
        @JsonProperty("timestamp")
        private String timestamp;
        
        @JsonProperty("level")
        private String level;
        
        @JsonProperty("service")
        private String service;
        
        @JsonProperty("env")
        private String env;

        @JsonProperty("traceId")
        private String traceId;

        @JsonProperty("host")
        private String host;

        @JsonProperty("pod")
        private String pod;

        @JsonProperty("logger")
        private String logger;
        
        @JsonProperty("message")
        private String message;
        
        @JsonProperty("exception")
        private String exception;

        @JsonProperty("uri")
        private String uri;

        @JsonProperty("httpStatus")
        private String httpStatus;

        @JsonProperty("costMs")
        private String costMs;

        @JsonProperty("rawFields")
        private Map<String, String> rawFields = new LinkedHashMap<>();
    }

    @Data
    public static class QueryLogsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("region")
        private String region;
        
        @JsonProperty("topicId")
        private String topicId;

        @JsonProperty("topicName")
        private String topicName;
        
        @JsonProperty("query")
        private String query;

        @JsonProperty("startTime")
        private String startTime;

        @JsonProperty("endTime")
        private String endTime;

        @JsonProperty("alertName")
        private String alertName;

        @JsonProperty("targetService")
        private String targetService;

        @JsonProperty("traceId")
        private String traceId;

        @JsonProperty("queryTemplate")
        private String queryTemplate;

        @JsonProperty("statistics")
        private LogStatistics statistics;

        @JsonProperty("summary")
        private String summary;

        @JsonProperty("suggestedNextAction")
        private String suggestedNextAction;

        @JsonProperty("evidenceSamples")
        private List<LogEntry> evidenceSamples;
        
        @JsonProperty("logs")
        private List<LogEntry> logs;
        
        @JsonProperty("total")
        private int total;
        
        @JsonProperty("message")
        private String message;
    }
    
    @Data
    public static class LogStatistics {
        @JsonProperty("totalMatched")
        private int totalMatched;

        @JsonProperty("levelDistribution")
        private Map<String, Integer> levelDistribution;

        @JsonProperty("topExceptions")
        private List<NameCount> topExceptions;

        @JsonProperty("topMessagePatterns")
        private List<MessagePatternCount> topMessagePatterns;

        @JsonProperty("topServices")
        private Map<String, Integer> topServices;

        @JsonProperty("topUris")
        private Map<String, Integer> topUris;
    }

    @Data
    public static class NameCount {
        @JsonProperty("name")
        private String name;

        @JsonProperty("count")
        private int count;

        public NameCount(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    @Data
    public static class MessagePatternCount {
        @JsonProperty("pattern")
        private String pattern;

        @JsonProperty("count")
        private int count;

        public MessagePatternCount(String pattern, int count) {
            this.pattern = pattern;
            this.count = count;
        }
    }

    @Data
    public static class LogTopicInfo {
        @JsonProperty("topicName")
        private String topicName;

        @JsonProperty("topicId")
        private String topicId;
        
        @JsonProperty("description")
        private String description;
        
        @JsonProperty("exampleQueries")
        private List<String> exampleQueries;
        
        @JsonProperty("relatedAlerts")
        private List<String> relatedAlerts;
    }
    
    @Data
    public static class LogTopicsOutput {
        @JsonProperty("success")
        private boolean success;
        
        @JsonProperty("topics")
        private List<LogTopicInfo> topics;
        
        @JsonProperty("availableRegions")
        private List<String> availableRegions;
        
        @JsonProperty("defaultRegion")
        private String defaultRegion;

        @JsonProperty("defaultTopicId")
        private String defaultTopicId;

        @JsonProperty("defaultTopicName")
        private String defaultTopicName;

        @JsonProperty("standardFields")
        private List<String> standardFields;
        
        @JsonProperty("message")
        private String message;
    }
}
