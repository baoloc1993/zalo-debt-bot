package com.example.zalobot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DebtAnalysisResult {
    private boolean isDebtRelated;
    private String analysisJson;
}
