package com.copilotguard.prompt;

import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.Template;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PromptRenderer {

    private static final Mustache.Compiler COMPILER = Mustache.compiler().escapeHTML(false);

    public String render(PromptTemplate template, Map<String, Object> context) {
        Template compiled = COMPILER.compile(template.content());
        return compiled.execute(context);
    }
}
