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
 * 会话摘要服务。
 * 当短期窗口溢出时，将旧摘要与即将淘汰的历史消息增量压缩为新的摘要，
 * 用于保留事实、上下文关系、关键结论与未解决事项。
 */
@Service
public class ConversationSummaryService {

    private static final Logger logger = LoggerFactory.getLogger(ConversationSummaryService.class);

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Value("${conversation-summary.enabled:true}")
    private boolean enabled;

    @Value("${conversation-summary.model:qwen3-30b-a3b-instruct-2507}")
    private String model;

    @Value("${conversation-summary.max-length:1200}")
    private int maxLength;

    private final Generation generation = new Generation();

    public SummaryResult summarize(String oldSummary, List<Map<String, String>> evictedMessages) {
        String safeOldSummary = defaultString(oldSummary).trim();
        List<Map<String, String>> safeMessages = evictedMessages == null ? List.of() : evictedMessages;

        if (!enabled) {
            return SummaryResult.disabled(safeOldSummary);
        }

        if (safeMessages.isEmpty()) {
            return SummaryResult.skipped(safeOldSummary, "no evicted messages");
        }

        try {
            String prompt = buildSummaryPrompt(safeOldSummary, safeMessages);
            String generated = callSummaryModel(prompt);
            String sanitized = sanitizeSummary(generated, safeOldSummary, safeMessages);
            logger.info("会话摘要更新完成，model={}, oldLength={}, newLength={}, evictedCount={}",
                    model, safeOldSummary.length(), sanitized.length(), safeMessages.size());
            return SummaryResult.updated(sanitized);
        } catch (Exception e) {
            logger.warn("会话摘要更新失败，保留旧摘要: {}", e.getMessage());
            return SummaryResult.failed(safeOldSummary, e.getMessage());
        }
    }

    private String buildSummaryPrompt(String oldSummary, List<Map<String, String>> evictedMessages) {
        StringBuilder historyText = new StringBuilder();
        for (Map<String, String> item : evictedMessages) {
            String role = defaultString(item.get("role"));
            String content = defaultString(item.get("content")).trim();
            if (content.isEmpty()) {
                continue;
            }
            if ("user".equals(role)) {
                historyText.append("用户: ").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                historyText.append("助手: ").append(content).append("\n");
            }
        }

        return "你是一个会话记忆压缩助手，任务是把旧摘要与即将淘汰的历史消息合并为一段新的会话摘要。\n"
                + "请严格遵守以下要求：\n"
                + "1. 只保留后续回答真正需要的内容：用户目标、已确认事实、关键结论、仍待解决问题。\n"
                + "2. 删除寒暄、客套话、重复描述和无关细节。\n"
                + "3. 不要编造对话中没有出现的新信息。\n"
                + "4. 输出使用简洁中文，控制在 " + maxLength + " 字以内。\n"
                + "5. 优先使用下面的结构：\n"
                + "用户目标：...\n"
                + "已确认事实：...\n"
                + "关键结论：...\n"
                + "待继续问题：...\n"
                + "6. 如果某一项没有有效信息，可以省略该项。\n"
                + "7. 只输出摘要正文，不要输出解释、前缀、Markdown 代码块。\n\n"
                + "【旧摘要】\n"
                + (oldSummary.isEmpty() ? "（无）" : oldSummary)
                + "\n\n【即将淘汰的历史消息】\n"
                + historyText
                + "\n请输出新的会话摘要：";
    }

    private String callSummaryModel(String prompt) throws NoApiKeyException, ApiException, InputRequiredException {
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

    private String sanitizeSummary(String generatedSummary,
                                   String oldSummary,
                                   List<Map<String, String>> evictedMessages) {
        String sanitized = defaultString(generatedSummary).trim();
        if (sanitized.startsWith("```")) {
            sanitized = sanitized.replace("```", "").trim();
        }
        sanitized = sanitized.replace("\r", " ").trim();
        sanitized = sanitized.replaceAll("\n{3,}", "\n\n");
        sanitized = sanitized.replaceAll("^['\"“”‘’]+|['\"“”‘’]+$", "").trim();

        if (sanitized.isEmpty()) {
            return fallbackSummary(oldSummary, evictedMessages);
        }

        if (sanitized.length() > maxLength) {
            sanitized = sanitized.substring(0, maxLength).trim();
        }

        return sanitized;
    }

    private String fallbackSummary(String oldSummary, List<Map<String, String>> evictedMessages) {
        StringBuilder builder = new StringBuilder();
        if (!defaultString(oldSummary).trim().isEmpty()) {
            builder.append(oldSummary.trim());
        }
        for (Map<String, String> item : evictedMessages) {
            String role = defaultString(item.get("role"));
            String content = defaultString(item.get("content")).trim();
            if (content.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n");
            }
            if ("user".equals(role)) {
                builder.append("用户提到：").append(content);
            } else if ("assistant".equals(role)) {
                builder.append("助手结论：").append(content);
            }
        }

        String fallback = builder.toString().trim();
        if (fallback.length() > maxLength) {
            fallback = fallback.substring(0, maxLength).trim();
        }
        return fallback;
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    @Getter
    public static class SummaryResult {
        private final String summary;
        private final boolean updated;
        private final boolean enabled;
        private final String reason;

        private SummaryResult(String summary, boolean updated, boolean enabled, String reason) {
            this.summary = summary;
            this.updated = updated;
            this.enabled = enabled;
            this.reason = reason;
        }

        public static SummaryResult updated(String summary) {
            return new SummaryResult(summary, true, true, "ok");
        }

        public static SummaryResult failed(String summary, String reason) {
            return new SummaryResult(summary, false, true, reason);
        }

        public static SummaryResult skipped(String summary, String reason) {
            return new SummaryResult(summary, false, true, reason);
        }

        public static SummaryResult disabled(String summary) {
            return new SummaryResult(summary, false, false, "conversation summary disabled");
        }
    }
}
