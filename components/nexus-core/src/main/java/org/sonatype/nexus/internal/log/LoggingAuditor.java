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
package org.sonatype.nexus.internal.log;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.common.log.LoggerLevel;
import org.sonatype.nexus.common.log.LoggerLevelChangedEvent;
import org.sonatype.nexus.common.log.LoggersResetEvent;
import org.sonatype.nexus.thread.internal.MDCUtils;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * Logging auditor.
 *
 * @since 3.1
 */
@Named
@Singleton
public class LoggingAuditor
    extends AuditorSupport
    implements EventAware
{
  public static final String DOMAIN = "logging";
  
  /**
   * Virtual thread executor for asynchronous event processing.
   * Using virtual threads provides high concurrency with minimal resource usage.
   */
  private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Handles logger reset events by creating and recording audit data.
   * Uses virtual threads for asynchronous processing with MDC context propagation.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final LoggersResetEvent event) {
    if (isRecording()) {
      // Create audit data with structured information
      AuditData data = createAuditData(DOMAIN, "reset", SYSTEM_CONTEXT);
      
      // Log the event using String Templates for structured logging
      if (log.isDebugEnabled()) {
        log.debug(STR."Loggers reset event received");
      }
      
      // Process asynchronously using virtual threads with MDC context propagation
      virtualThreadExecutor.execute(MDCUtils.withMdcContext(() -> record(data)));
    }
  }

  /**
   * Handles logger level changed events by creating and recording audit data.
   * Uses virtual threads for asynchronous processing with MDC context propagation.
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final LoggerLevelChangedEvent event) {
    if (isRecording()) {
      String logger = event.getLogger();
      LoggerLevel level = event.getLevel();

      // Create audit data with structured information using String Templates
      AuditData data = createAuditData(DOMAIN, CHANGED_TYPE, logger);

      // Add attributes using String Templates for structured logging
      Map<String, Object> attributes = data.getAttributes();
      attributes.put("logger", logger);
      attributes.put("level", string(level));
      
      // Log the event using String Templates for structured logging
      if (log.isDebugEnabled()) {
        log.debug(STR."Logger level changed: logger=\{logger}, level=\{level}");
      }
      
      // Process asynchronously using virtual threads with MDC context propagation
      virtualThreadExecutor.execute(MDCUtils.withMdcContext(() -> record(data)));
    }
  }
  
  /**
   * Creates an AuditData object with the specified domain, type, and context.
   * Optimized for concurrent execution in virtual threads.
   * Uses Java 21 String Templates for structured logging when needed.
   *
   * @param domain the audit domain
   * @param type the audit type
   * @param context the audit context
   * @return a new AuditData instance
   */
  private AuditData createAuditData(String domain, String type, String context) {
    AuditData data = new AuditData();
    data.setDomain(domain);
    data.setType(type);
    data.setContext(context);
    
    // Log creation of audit data using String Templates for structured logging
    if (log.isDebugEnabled()) {
      log.debug(STR."Creating audit data: domain=\{domain}, type=\{type}, context=\{context}");
    }
    
    return data;
  }
}