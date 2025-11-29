package com.example.zalobot.model.gemini;

import java.util.List;

public class GeminiRequest {
    private List<GeminiMessage> contents;

    public GeminiRequest(List<GeminiMessage> contents) {
        this.contents = contents;
    }

    public List<GeminiMessage> getContents() {
        return contents;
    }

    public void setContents(List<GeminiMessage> contents) {
        this.contents = contents;
    }
}
