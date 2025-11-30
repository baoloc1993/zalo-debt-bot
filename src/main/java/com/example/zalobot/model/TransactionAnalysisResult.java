package com.example.zalobot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionAnalysisResult {
    private String transactionType;
    private String debtDirection; // "USER_OWES_OTHER" or "OTHER_OWES_USER"
    private boolean isTransactionRelated;
    private boolean isSummaryRequest;
    private String summaryType;
    private String analysisJson;
}
