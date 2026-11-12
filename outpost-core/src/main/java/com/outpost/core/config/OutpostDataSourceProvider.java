package com.outpost.core.config;

import javax.sql.DataSource;

/**
 * 数据源提供者，由接入方在无法通过容器解析唯一数据源时显式提供。
 */
public interface OutpostDataSourceProvider {

    DataSource getDataSource();
}
