package com.outpost.core.admin;

import java.time.LocalDateTime;

/**
 * 消息查询条件。
 *
 * <p>默认过滤 {@code message_type=1} 的辅助消息（延迟双删第二次），
 * 需要查看时显式设置 {@link #setIncludeAuxiliary(boolean)} 为 {@code true}。</p>
 */
public class MessageQuery {

    private String bizType;
    private String bizKey;
    private Integer status;
    private Integer deadReason;
    private LocalDateTime createTimeFrom;
    private LocalDateTime createTimeTo;
    private boolean includeAuxiliary = false;
    private int pageNo = 1;
    private int pageSize = 20;

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getBizKey() {
        return bizKey;
    }

    public void setBizKey(String bizKey) {
        this.bizKey = bizKey;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getDeadReason() {
        return deadReason;
    }

    public void setDeadReason(Integer deadReason) {
        this.deadReason = deadReason;
    }

    public LocalDateTime getCreateTimeFrom() {
        return createTimeFrom;
    }

    public void setCreateTimeFrom(LocalDateTime createTimeFrom) {
        this.createTimeFrom = createTimeFrom;
    }

    public LocalDateTime getCreateTimeTo() {
        return createTimeTo;
    }

    public void setCreateTimeTo(LocalDateTime createTimeTo) {
        this.createTimeTo = createTimeTo;
    }

    public boolean isIncludeAuxiliary() {
        return includeAuxiliary;
    }

    public void setIncludeAuxiliary(boolean includeAuxiliary) {
        this.includeAuxiliary = includeAuxiliary;
    }

    public int getPageNo() {
        return pageNo;
    }

    public void setPageNo(int pageNo) {
        this.pageNo = pageNo;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    /**
     * 校验并归一化分页参数，返回 offset。
     */
    public long offset() {
        if (pageNo < 1) {
            throw new IllegalArgumentException("pageNo 必须从 1 开始，实际值: " + pageNo);
        }
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("pageSize 取值区间 [1, 1000]，实际值: " + pageSize);
        }
        return (long) (pageNo - 1) * pageSize;
    }
}
