package com.breakdowngrid.config;

import com.atlassian.plugins.osgi.javaconfig.configs.beans.ModuleFactoryBean;
import com.atlassian.plugins.osgi.javaconfig.configs.beans.PluginAccessorBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Мінімальна Spring-конфігурація плагіна. Модулі (тип поля, servlet, REST) інстанціює сам Jira
 * за атрибутом class і працюють вони через ComponentAccessor — власних Spring-бінів не потрібно.
 * Тут лише вносимо ModuleFactory / PluginAccessor у контекст плагіна (стандартна OSGi-обв'язка).
 */
@Configuration
@Import({
        ModuleFactoryBean.class,
        PluginAccessorBean.class
})
public class BreakdownGridConfig {
}
