package com.outpost.core.admin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 自有分页结果，避免为运维接口引入 spring-data-commons。
 */
public final class OutpostPage<T> {

    private final List<T> items;
    private final long total;
    private final int pageNo;
    private final int pageSize;

    public OutpostPage(List<T> items, long total, int pageNo, int pageSize) {
        this.items = items == null
                ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(items));
        this.total = Math.max(0L, total);
        this.pageNo = pageNo;
        this.pageSize = pageSize;
    }

    public List<T> getItems() {
        return items;
    }

    public long getTotal() {
        return total;
    }

    public int getPageNo() {
        return pageNo;
    }

    public int getPageSize() {
        return pageSize;
    }

    public long getTotalPages() {
        if (pageSize < 1) {
            return 0L;
        }
        return (total + pageSize - 1L) / pageSize;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
