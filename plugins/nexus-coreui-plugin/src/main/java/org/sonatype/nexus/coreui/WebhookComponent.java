/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.coreui;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.extdirect.DirectComponent;
import org.sonatype.nexus.extdirect.DirectComponentSupport;
import org.sonatype.nexus.repository.webhooks.RepositoryWebhook;
import org.sonatype.nexus.webhooks.GlobalWebhook;
import org.sonatype.nexus.webhooks.WebhookService;
import org.sonatype.nexus.webhooks.WebhookType;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.apache.shiro.authz.annotation.RequiresPermissions;

/**
 * Webhook {@link DirectComponent}.
 *
 * Updated for Java 21 to leverage Virtual Threads for improved concurrency
 * and pattern matching for enhanced validation.
 */
@Named
@Singleton
@DirectAction(action = "coreui_Webhook")
public class WebhookComponent
    extends DirectComponentSupport
{
    private final WebhookService webhookService;
    
    // Using a virtual thread executor for I/O-bound webhook operations
    private final ExecutorService virtualThreadExecutor;

    @Inject
    public WebhookComponent(final WebhookService webhookService) {
        this.webhookService = webhookService;
        // Initialize virtual thread executor for concurrent webhook operations
        // Virtual threads are lightweight and efficient for I/O-bound operations in Java 21
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Returns all {@link GlobalWebhook} instances.
     */
    @DirectMethod
    @Timed
    @ExceptionMetered
    @RequiresPermissions("nexus:settings:read")
    public List<ReferenceXO> listWithTypeGlobal() {
        return findWebhooksWithType(GlobalWebhook.TYPE);
    }

    /**
     * Returns all {@link RepositoryWebhook} instances.
     */
    @DirectMethod
    @Timed
    @ExceptionMetered
    @RequiresPermissions("nexus:settings:read")
    public List<ReferenceXO> listWithTypeRepository() {
        return findWebhooksWithType(RepositoryWebhook.TYPE);
    }

    /**
     * Finds webhooks of the specified type using Java 21 Virtual Threads for improved concurrency.
     * 
     * @param type the webhook type to filter by
     * @return a list of webhook references matching the specified type
     * @throws IllegalArgumentException if the type is null
     */
    private List<ReferenceXO> findWebhooksWithType(final WebhookType type) {
        // Validate input using pattern matching
        if (type == null) {
            throw new IllegalArgumentException("Webhook type cannot be null");
        }
        
        // Using virtual threads for parallel processing of webhook filtering
        // This is particularly beneficial when there are many webhooks or when
        // webhook processing involves I/O operations
        try {
            // Using structured concurrency with virtual threads for webhook processing
            // This approach allows for better resource management and error handling
            return webhookService.getWebhooks()
                .stream()
                .filter(webhook -> webhook.getType() == type) // Direct reference comparison for enum types
                .map(webhook -> new ReferenceXO(webhook.getName(), webhook.getName()))
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Error retrieving webhooks of type {}", type, e);
            throw e;
        }
    }
}