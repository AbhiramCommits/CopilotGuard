package com.copilotguard.llm;

import com.copilotguard.domain.CommentCategory;
import com.copilotguard.domain.Severity;

public record ReviewCommentSuggestion(
        String file,
        Integer line,
        Severity severity,
        CommentCategory category,
        String body,
        String suggestedFix) {}
