package com.novel.browse.config;

import com.novel.browse.data.DbSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Makes the JPA {@code EntityManagerFactory} wait for {@link DbSnapshot}: the snapshot has to be loaded
 * <em>before</em> Hibernate updates the schema, otherwise the restored tables keep the schema of the previous
 * version and columns added by an upgrade would be missing.
 * <p>
 * Runs as a (non-priority) ordered {@link BeanFactoryPostProcessor}, i.e. after auto-configuration and
 * component scanning registered their bean definitions. Note that the {@code EntityManagerFactory} is a
 * {@code FactoryBean}, so its name may carry the {@code &} prefix and has to be normalized.
 */
@Component
public class DbSnapshotOrder implements BeanFactoryPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DbSnapshotOrder.class);

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        List<String> targets = new ArrayList<>(Arrays.asList(
                beanFactory.getBeanNamesForType(LocalContainerEntityManagerFactoryBean.class)));
        if (targets.isEmpty() && beanFactory.containsBeanDefinition("entityManagerFactory")) {
            targets.add("entityManagerFactory");
        }
        for (String name : targets) {
            String beanName = BeanFactoryUtils.transformedBeanName(name);
            if (!beanFactory.containsBeanDefinition(beanName)) {
                continue;
            }
            BeanDefinition definition = beanFactory.getBeanDefinition(beanName);
            String[] dependsOn = definition.getDependsOn();
            if (dependsOn != null && Arrays.asList(dependsOn).contains(DbSnapshot.BEAN_NAME)) {
                continue;
            }
            String[] merged = dependsOn == null || dependsOn.length == 0
                    ? new String[]{DbSnapshot.BEAN_NAME}
                    : Arrays.copyOf(dependsOn, dependsOn.length + 1);
            merged[merged.length - 1] = DbSnapshot.BEAN_NAME;
            definition.setDependsOn(merged);
            log.debug("{} now depends on {}", beanName, DbSnapshot.BEAN_NAME);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
