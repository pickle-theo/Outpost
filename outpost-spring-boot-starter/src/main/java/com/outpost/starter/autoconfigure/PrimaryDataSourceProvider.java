package com.outpost.starter.autoconfigure;

import com.outpost.core.config.OutpostConfigurationException;
import com.outpost.core.config.OutpostDataSourceProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认数据源解析：单实例直接用；多实例必须且只能有一个 {@link Primary}，
 * 否则拒绝启动，避免「消息独立提交、业务回滚」的静默错误。
 *
 * <p>业务可通过提供自己的 {@link OutpostDataSourceProvider} bean 完全接管。</p>
 */
public class PrimaryDataSourceProvider implements OutpostDataSourceProvider {

    private final ApplicationContext context;

    private volatile DataSource resolved;

    public PrimaryDataSourceProvider(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public DataSource getDataSource() {
        DataSource current = resolved;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (resolved == null) {
                resolved = resolve();
            }
            return resolved;
        }
    }

    private DataSource resolve() {
        String[] names = context.getBeanNamesForType(DataSource.class, false, false);
        if (names.length == 0) {
            throw new OutpostConfigurationException(
                    "Outpost 未发现任何 DataSource bean，请先配置数据源或提供 "
                            + "OutpostDataSourceProvider；消息表必须与业务表共用同一个 DataSource 实例");
        }
        if (names.length == 1) {
            return context.getBean(names[0], DataSource.class);
        }
        List<String> primaryNames = new ArrayList<String>();
        for (String name : names) {
            if (context.findAnnotationOnBean(name, Primary.class) != null) {
                primaryNames.add(name);
            }
        }
        if (primaryNames.size() == 1) {
            return context.getBean(primaryNames.get(0), DataSource.class);
        }
        throw new OutpostConfigurationException(
                "Outpost 检测到多个 DataSource bean " + java.util.Arrays.toString(names)
                        + "，但 @Primary 数量为 " + primaryNames.size()
                        + "，无法唯一解析。请为业务主库标记 @Primary，或提供 "
                        + "OutpostDataSourceProvider bean 显式指定数据源");
    }
}
