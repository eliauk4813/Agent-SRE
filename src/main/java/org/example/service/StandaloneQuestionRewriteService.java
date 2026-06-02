package org.example.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Standalone Question Rewrite 服务。
 * 将当前问题结合多轮历史，改写为完整、自包含、适合检索的问题
 * 仅用于内部文档知识库检索，不改变用户原始问题语义。
 */
@Service
public class StandaloneQuestionRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(StandaloneQuestionRewriteService.class);

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Value("${query-rewrite.enabled:true}")
    private boolean enabled;

    @Value("${query-rewrite.model:qwen3-max}")
    private String model;

    @Value("${query-rewrite.max-history-messages:6}")
    private int maxHistoryMessages;

    private final Generation generation = new Generation();

    public RewriteResult rewrite(String currentQuestion, List<Map<String, String>> history) {
        String safeQuestion = defaultString(currentQuestion).trim();
        List<Map<String, String>> safeHistory = history == null ? List.of() : history;

        if (!enabled) {
            return RewriteResult.notRewritten(safeQuestion, "query rewrite disabled");
        }

        if (safeQuestion.isEmpty()) {
            return RewriteResult.notRewritten(safeQuestion, "empty question");
        }

        if (safeHistory.isEmpty()) {
            return RewriteResult.notRewritten(safeQuestion, "no history");
        }

        try {
            String prompt = buildRewritePrompt(safeQuestion, safeHistory);
            String rewritten = callRewriteModel(prompt);
            String sanitized = sanitizeRewriteResult(rewritten, safeQuestion);

            if (sanitized.equals(safeQuestion)) {
                return RewriteResult.notRewritten(safeQuestion, "rewrite unchanged");
            }

            logger.info("Standalone rewrite 完成, original={}, rewritten={}", safeQuestion, sanitized);
            return RewriteResult.rewritten(safeQuestion, sanitized);
        } catch (Exception e) {
            logger.warn("Standalone rewrite 失败，回退原问题: {}", e.getMessage());
            return RewriteResult.notRewritten(safeQuestion, "rewrite failed: " + e.getMessage());
        }
    }

    private String buildRewritePrompt(String currentQuestion, List<Map<String, String>> history) {
        StringBuilder historyText = new StringBuilder();
        List<Map<String, String>> trimmedHistory = trimHistory(history);

        for (Map<String, String> item : trimmedHistory) {
            String role = defaultString(item.get("role"));
            String content = defaultString(item.get("content"));
            if (content.isBlank()) {
                continue;
            }
            if ("user".equals(role)) {
                historyText.append("用户: ").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                historyText.append("助手: ").append(content).append("\n");
            }
        }

        return "你是一个查询改写助手，任务是把当前用户问题改写为适合内部知识库检索的独立问题。\n"
                + "请严格遵守以下规则：\n"
                + "1. 输出必须是一个单独的问题句子，不要输出解释、分析、前缀或 Markdown。\n"
                + "2. 必须结合历史对话补全指代、省略和上下文，使结果完整、自包含。\n"
                + "3. 尽量保留用户原意，不要扩展超出对话中明确出现的新事实。\n"
                + "4. 如果当前问题已经完整清晰，原样返回或做最小幅度润色。\n"
                + "5. 结果目标是用于内部技术文档检索，措辞应偏向故障现象、告警名称、排查步骤、处理方案等表达。\n\n"
                + "对话历史：\n"
                + historyText
                + "\n当前用户问题：\n"
                + currentQuestion
                + "\n\n请输出改写后的独立检索问题：";
    }

    private List<Map<String, String>> trimHistory(List<Map<String, String>> history) {
        if (history.size() <= maxHistoryMessages) {
            return history;
        }
        return new ArrayList<>(history.subList(history.size() - maxHistoryMessages, history.size()));
    }

    private String callRewriteModel(String prompt) throws NoApiKeyException, ApiException, InputRequiredException {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder()
                .role(Role.USER.getValue())
                .content(prompt)
                .build());

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .resultFormat("message")
                .messages(messages)
                .build();

        GenerationResult result = generation.call(param);
        if (result == null
                || result.getOutput() == null
                || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()
                || result.getOutput().getChoices().get(0).getMessage() == null) {
            return "";
        }

        return defaultString(result.getOutput().getChoices().get(0).getMessage().getContent());
    }

    private String sanitizeRewriteResult(String rewritten, String fallbackQuestion) {
        String sanitized = defaultString(rewritten).trim();
        if (sanitized.startsWith("```")) {
            sanitized = sanitized.replace("```", "").trim();
        }
        sanitized = sanitized.replace("\r", " ").replace("\n", " ").trim();
        sanitized = sanitized.replaceAll("^['\"“”‘’]+|['\"“”‘’]+$", "").trim();

        if (sanitized.isEmpty()) {
            return fallbackQuestion;
        }

        if (sanitized.length() > 200) {
            logger.warn("rewrite 结果过长，回退原问题: {}", sanitized);
            return fallbackQuestion;
        }

        return sanitized;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    @Getter
    public static class RewriteResult {
        private final String originalQuestion;
        private final String standaloneQuestion;
        private final boolean rewritten;
        private final String reason;

        private RewriteResult(String originalQuestion, String standaloneQuestion, boolean rewritten, String reason) {
            this.originalQuestion = originalQuestion;
            this.standaloneQuestion = standaloneQuestion;
            this.rewritten = rewritten;
            this.reason = reason;
        }

        public static RewriteResult rewritten(String originalQuestion, String standaloneQuestion) {
            return new RewriteResult(originalQuestion, standaloneQuestion, true, "ok");
        }

        public static RewriteResult notRewritten(String originalQuestion, String reason) {
            return new RewriteResult(originalQuestion, originalQuestion, false, reason);
        }
    }
}
