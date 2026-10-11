package io.github.baoloc1993.zalobot.model;

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
    private String transactionDate;
    private Double amount;
    private String reason;
    private String appUserName;
    private String zaloMessageId;

    public boolean isTransactionRelated() {
        return isTransactionRelated;
    }

    public String getAnalysisJson() {
        return analysisJson;
    }

    public String getZaloMessageId() {
        return zaloMessageId;
    }

    public void setZaloMessageId(String zaloMessageId) {
        this.zaloMessageId = zaloMessageId;
    }

    public String getTransactionType() {
        return transactionType;
    }

    public String getDebtDirection() {
        return debtDirection;
    }

    public String getAppUserName() {
        return appUserName;
    }

    public Double getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }

    public String getTransactionDate() {
        return transactionDate;
    }
}
