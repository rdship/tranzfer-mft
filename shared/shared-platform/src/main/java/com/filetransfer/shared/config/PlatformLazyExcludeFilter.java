package com.filetransfer.shared.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.LazyInitializationExcludeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized filter that keeps {@code spring.main.lazy-initialization=true} globally
 * but automatically excludes infrastructure beans that MUST be eager.
 *
 * <p>This is the official Spring Boot 3.2+ mechanism — one filter, one place, zero scattered
 * {@code @Lazy(false)} annotations. Detects beans by their method-level annotations:
 * <ul>
 *   <li>{@code @RabbitListener} — must register with broker at startup</li>
 *   <li>{@code @Scheduled} — must register timers at startup</li>
 *   <li>{@code @EventListener} — must register before events fire</li>
 *   <li>{@code @PostConstruct} — must run initialization at startup</li>
 * </ul>
 *
 * <p>Also excludes:
 * <ul>
 *   <li>Any class whose name ends with "Config" or "Configuration" (infrastructure beans)</li>
 *   <li>Any class implementing Spring's {@code SmartInitializingSingleton}</li>
 * </ul>
 *
 * <p><b>Why this exists:</b> {@code lazy-initialization=true} saves 6-10s boot time by deferring
 * 400+ bean instantiations. But ~60 beans (RabbitMQ, schedulers, event listeners) must be eager
 * or the messaging pipeline, cron jobs, and event handlers silently break. This filter gives us
 * both: fast boot + working infrastructure.
 *
 * @see org.springframework.boot.LazyInitializationExcludeFilter
 */
@Slf4j
@Configuration
public class PlatformLazyExcludeFilter {

    /** Annotations that indicate a bean MUST be eagerly initialized. */
    private static final Set<Class<? extends Annotation>> EAGER_ANNOTATIONS = Set.of(
            RabbitListener.class,
            Scheduled.class,
            EventListener.class,
            PostConstruct.class
    );

    /** Cache: avoid re-scanning the same class on every bean. */
    private static final ConcurrentHashMap<Class<?>, Boolean> CACHE = new ConcurrentHashMap<>();

    @Bean
    static LazyInitializationExcludeFilter platformEagerBeanFilter() {
        return (beanName, beanDefinition, beanType) -> {
            if (beanType == null) return false;

            // 1. Configuration classes (declare Queue, Exchange, Binding, SecurityFilterChain)
            if (beanType.isAnnotationPresent(Configuration.class)) {
                return true;
            }

            // 2. SmartInitializingSingleton — Spring contract: always eager
            if (org.springframework.beans.factory.SmartInitializingSingleton.class
                    .isAssignableFrom(beanType)) {
                return true;
            }

            // 3. Check method-level annotations (cached per class)
            return CACHE.computeIfAbsent(beanType,
                    PlatformLazyExcludeFilter::hasEagerAnnotation);
        };
    }

    /**
     * Scan declared methods for any annotation that requires eager initialization.
     * Includes inherited methods from superclasses.
     */
    private static boolean hasEagerAnnotation(Class<?> beanType) {
        try {
            for (Method method : beanType.getDeclaredMethods()) {
                for (Class<? extends Annotation> annotation : EAGER_ANNOTATIONS) {
                    if (method.isAnnotationPresent(annotation)) {
                        log.debug("Excluding bean {} from lazy-init (method {} has @{})",
                                beanType.getSimpleName(), method.getName(),
                                annotation.getSimpleName());
                        return true;
                    }
                }
            }
            // Also check superclass (shared-platform beans extend base classes)
            Class<?> parent = beanType.getSuperclass();
            if (parent != null && parent != Object.class) {
                for (Method method : parent.getDeclaredMethods()) {
                    for (Class<? extends Annotation> annotation : EAGER_ANNOTATIONS) {
                        if (method.isAnnotationPresent(annotation)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            // If reflection fails, be safe — make it eager
            return true;
        }
        return false;
    }
}
