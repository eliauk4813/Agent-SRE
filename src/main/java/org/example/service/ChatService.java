package org.example.service;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 聊天服务
 * 封装 ReactAgent 对话的公共逻辑，包括模型创建、系统提示词构建、Agent 配置等
 */
@Service
public class ChatService {

    private static final Logger logger = LoggerFactory.getLogger(ChatService.class);

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // 本地日志工具可用时注册，也可直接查询真实 CLS
    private QueryLogsTools queryLogsTools;

    @Autowired
    private ToolCallbackProvider tools;

    @Autowired
    private StandaloneQuestionRewriteService standaloneQuestionRewriteService;

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    /**
     * 创建 DashScope API 实例
     */
    public DashScopeApi createDashScopeApi() {
        return DashScopeApi.builder()
                .apiKey(dashScopeApiKey)
                .build();
    }

    /**
     * 创建 ChatModel
     * @param temperature 控制随机性 (0.0-1.0)
     * @param maxToken 最大输出长度
     * @param topP 核采样参数
     */
    public DashScopeChatModel createChatModel(DashScopeApi dashScopeApi, double temperature, int maxToken, double topP) {
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(DashScopeChatModel.DEFAULT_MODEL_NAME)
                        .withTemperature(temperature)
                        .withMaxToken(maxToken)
                        .withTopP(topP)
                        .build())
                .build();
    }

    /**
     * 创建标准对话 ChatModel（默认参数）
     */
    public DashScopeChatModel createStandardChatModel(DashScopeApi dashScopeApi) {
        return createChatModel(dashScopeApi, 0.7, 2000, 0.9);
    }

    /**
     * 构建系统提示词（包含历史摘要与最近历史消息）
     * @param conversationSummary 会话摘要
     * @param history 最近历史消息列表
     * @return 完整的系统提示词
     */
    public String buildSystemPrompt(String conversationSummary,
                                    String conversationFacts,
                                    List<Map<String, String>> history) {
        StringBuilder systemPromptBuilder = new StringBuilder();

        systemPromptBuilder.append("你是一个专业的智能助手，可以获取当前时间、查询内部文档知识库、查询 Prometheus 告警，并查询腾讯云 CLS 中的结构化日志。\n");
        systemPromptBuilder.append("当用户询问时间相关问题时，使用 getCurrentDateTime 工具。\n");
        systemPromptBuilder.append("当用户需要查询公司内部文档、流程、最佳实践或技术指南时，使用 queryInternalDocs 工具。\n");
        systemPromptBuilder.append("当用户需要查询 Prometheus 告警、监控指标或系统告警状态时，使用 queryPrometheusAlerts 工具。\n");
        systemPromptBuilder.append("当用户需要按告警排查日志时，优先使用 queryLogsByAlert；当用户提供 traceId 时，使用 queryLogsByTraceId；只有在需要自定义检索条件时才使用 queryLogs。默认地域为 ap-guangzhou。\n\n");

        if (conversationFacts != null && !conversationFacts.trim().isEmpty()) {
            systemPromptBuilder.append("--- 历史关键事实记忆 ---\n");
            systemPromptBuilder.append(conversationFacts.trim()).append("\n");
            systemPromptBuilder.append("--- 历史关键事实记忆结束 ---\n\n");
        }

        if (conversationSummary != null && !conversationSummary.trim().isEmpty()) {
            systemPromptBuilder.append("--- 历史摘要记忆 ---\n");
            systemPromptBuilder.append(conversationSummary.trim()).append("\n");
            systemPromptBuilder.append("--- 历史摘要记忆结束 ---\n\n");
        }

        if (!history.isEmpty()) {
            systemPromptBuilder.append("--- 最近对话历史 ---\n");
            for (Map<String, String> msg : history) {
                String role = msg.get("role");
                String content = msg.get("content");
                if ("user".equals(role)) {
                    systemPromptBuilder.append("用户: ").append(content).append("\n");
                } else if ("assistant".equals(role)) {
                    systemPromptBuilder.append("助手: ").append(content).append("\n");
                }
            }
            systemPromptBuilder.append("--- 最近对话历史结束 ---\n\n");
        }

        systemPromptBuilder.append("请优先依据最近对话历史理解当前问题，并结合历史摘要记忆保持跨轮一致性。\n");
        systemPromptBuilder.append("如果你决定调用 queryInternalDocs，请优先围绕当前问题的核心故障现象、告警主题、排查步骤或处理方案来组织检索。");

        return systemPromptBuilder.toString();
    }

    public StandaloneQuestionRewriteService.RewriteResult prepareStandaloneQuestion(String question,
                                                                                    String conversationFacts,
                                                                                    List<Map<String, String>> history) {
        StandaloneQuestionRewriteService.RewriteResult rewriteResult =
                standaloneQuestionRewriteService.rewrite(question, conversationFacts, history);
        logger.info("文档检索 rewrite 结果, rewritten={}, reason={}, original={}, standalone={}",
                rewriteResult.isRewritten(),
                rewriteResult.getReason(),
                rewriteResult.getOriginalQuestion(),
                rewriteResult.getStandaloneQuestion());
        return rewriteResult;
    }

    /**
     * 动态构建方法工具数组
     * QueryLogsTools 统一作为日志查询入口，直接查询 CLS 或返回 mock 数据
     */
    public Object[] buildMethodToolsArray(StandaloneQuestionRewriteService.RewriteResult rewriteResult) {
        InternalDocsRewriteAdapter internalDocsRewriteAdapter = new InternalDocsRewriteAdapter(internalDocsTools, rewriteResult);
        if (queryLogsTools != null) {
            return new Object[]{dateTimeTools, internalDocsRewriteAdapter, queryMetricsTools, queryLogsTools};
        } else {
            return new Object[]{dateTimeTools, internalDocsRewriteAdapter, queryMetricsTools};
        }
    }

    /**
     * 获取工具回调列表，mcp服务提供的工具
     */
    public ToolCallback[] getToolCallbacks() {
        return tools.getToolCallbacks();
    }

    /**
     * 记录可用工具列表：mcp服务提供的工具
     */
    public void logAvailableTools() {
        ToolCallback[] toolCallbacks = tools.getToolCallbacks();
        logger.info("可用工具列表:");
        for (ToolCallback toolCallback : toolCallbacks) {
            logger.info(">>> {}", toolCallback.getToolDefinition().name());
        }
    }

    /**
     * 创建 ReactAgent
     * @param chatModel 聊天模型
     * @param systemPrompt 系统提示词
     * @param rewriteResult 文档检索独立问题改写结果
     * @return 配置好的 ReactAgent
     */
    public ReactAgent createReactAgent(DashScopeChatModel chatModel,
                                       String systemPrompt,
                                       StandaloneQuestionRewriteService.RewriteResult rewriteResult) {
        return ReactAgent.builder()
                .name("intelligent_assistant")
                .model(chatModel)
                .systemPrompt(systemPrompt)
                .methodTools(buildMethodToolsArray(rewriteResult))
                .tools(getToolCallbacks())
                .build();
    }

    /**
     * 执行 ReactAgent 对话（非流式）
     * @param agent ReactAgent 实例
     * @param question 用户问题
     * @return AI 回复
     */
    public String executeChat(ReactAgent agent, String question) throws GraphRunnerException {
        logger.info("执行 ReactAgent.call() - 自动处理工具调用");
        var response = agent.call(question);
        String answer = response.getText();
        logger.info("ReactAgent 对话完成，答案长度: {}", answer.length());
        return answer;
    }

    /**
     * 当前会话专用的文档检索工具包装器。
     * Agent 仍然调用 queryInternalDocs，但底层会使用预先生成的 standalone question 作为检索 query。
     */
    public static class InternalDocsRewriteAdapter {
        private final InternalDocsTools internalDocsTools;
        private final StandaloneQuestionRewriteService.RewriteResult rewriteResult;

        public InternalDocsRewriteAdapter(InternalDocsTools internalDocsTools,
                                          StandaloneQuestionRewriteService.RewriteResult rewriteResult) {
            this.internalDocsTools = internalDocsTools;
            this.rewriteResult = rewriteResult;
        }

        @org.springframework.ai.tool.annotation.Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
                "It uses a standalone rewritten retrieval query when conversation context contains references, ellipsis, or omitted subjects, then runs Hybrid RAG for retrieval.")
        public String queryInternalDocs(
                @org.springframework.ai.tool.annotation.ToolParam(description = "Search query describing what information you are looking for")
                String query) {
            String originalQuery = (query == null || query.trim().isEmpty())
                    ? rewriteResult.getOriginalQuestion()
                    : query.trim();
            String retrievalQuery = chooseRetrievalQuery(originalQuery);
            return internalDocsTools.queryInternalDocsWithRewrite(
                    originalQuery,
                    retrievalQuery,
                    rewriteResult.isRewritten() && !retrievalQuery.equals(originalQuery),
                    rewriteResult.getReason());
        }

        private String chooseRetrievalQuery(String toolQuery) {
            String normalizedToolQuery = toolQuery == null ? "" : toolQuery.trim();
            if (!rewriteResult.isRewritten()) {
                return normalizedToolQuery;
            }

            String standaloneQuestion = rewriteResult.getStandaloneQuestion() == null
                    ? ""
                    : rewriteResult.getStandaloneQuestion().trim();
            if (standaloneQuestion.isEmpty()) {
                return normalizedToolQuery;
            }
            if (normalizedToolQuery.isEmpty()) {
                return standaloneQuestion;
            }
            if (looksLikeContextDependentQuestion(normalizedToolQuery)) {
                return standaloneQuestion;
            }
            if (isStandaloneMoreInformative(standaloneQuestion, normalizedToolQuery)) {
                return standaloneQuestion;
            }
            return normalizedToolQuery;
        }

        private boolean isStandaloneMoreInformative(String standaloneQuestion, String toolQuery) {
            String standalone = normalizeForComparison(standaloneQuestion);
            String tool = normalizeForComparison(toolQuery);
            if (standalone.equals(tool)) {
                return false;
            }
            if (standalone.length() < Math.max(12, tool.length())) {
                return false;
            }
            if (standalone.contains(tool) && standalone.length() <= tool.length() + 80) {
                return true;
            }

            int standaloneSignalCount = countRetrievalSignals(standalone);
            int toolSignalCount = countRetrievalSignals(tool);
            return standalone.length() >= tool.length() + 8 && standaloneSignalCount >= toolSignalCount;
        }

        private String normalizeForComparison(String value) {
            return value == null ? "" : value.trim().replaceAll("\\s+", " ");
        }

        private int countRetrievalSignals(String query) {
            int count = 0;
            String normalized = query == null ? "" : query.toLowerCase();
            String[] signals = {
                    "告警", "故障", "异常", "错误", "报错", "不可用", "慢", "超时", "延迟",
                    "cpu", "内存", "memory", "磁盘", "disk", "oom", "503", "500", "日志",
                    "排查", "处理", "解决", "恢复", "服务", "接口", "trace", "traceid"
            };
            for (String signal : signals) {
                if (normalized.contains(signal)) {
                    count++;
                }
            }
            return count;
        }

        private boolean looksLikeContextDependentQuestion(String query) {
            String normalized = query == null ? "" : query.trim();
            if (normalized.isEmpty()) {
                return true;
            }
            if (normalized.length() <= 8 && countRetrievalSignals(normalized) == 0) {
                return true;
            }
            String lower = normalized.toLowerCase();
            return normalized.contains("这个")
                    || normalized.contains("那个")
                    || normalized.contains("这些")
                    || normalized.contains("那些")
                    || normalized.contains("上述")
                    || normalized.contains("前面")
                    || normalized.contains("刚才")
                    || normalized.contains("这个呢")
                    || normalized.contains("那个呢")
                    || normalized.contains("它怎么")
                    || normalized.startsWith("那")
                    || normalized.startsWith("这")
                    || normalized.startsWith("它")
                    || normalized.startsWith("对于这个")
                    || normalized.startsWith("关于这个")
                    || lower.contains(" this ")
                    || lower.contains(" that ")
                    || lower.contains(" it ")
                    || lower.startsWith("this ")
                    || lower.startsWith("that ")
                    || lower.startsWith("it ")
                    || lower.equals("this")
                    || lower.equals("that")
                    || lower.equals("it");
        }
    }
}
