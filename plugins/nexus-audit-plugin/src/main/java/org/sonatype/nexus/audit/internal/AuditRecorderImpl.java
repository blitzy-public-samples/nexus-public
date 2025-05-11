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
package org.sonatype.nexus.audit.internal;

import java.util.Date;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditDataRecordedEvent;
import org.sonatype.nexus.audit.AuditRecorder;
import org.sonatype.nexus.audit.InitiatorProvider;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.node.NodeAccess;
import org.sonatype.nexus.security.UserIdHelper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.Objects.requireNonNull;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.AUDIT_LOG_ONLY;

// Java 21 imports for Virtual Threads and String Templates
import static java.lang.StringTemplate.STR;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;

/**
 * Default {@link AuditRecorder} implementation.
 * 
 * Updated for Java 21 to leverage Virtual Threads for non-blocking I/O operations
 * and String Templates for improved logging readability.
 *
 * @since 3.1
 */
@Named
@Singleton
public class AuditRecorderImpl
    extends ComponentSupport
    implements AuditRecorder
{
  private final EventManager eventManager;

  private final NodeAccess nodeAccess;

  private final InitiatorProvider initiatorProvider;
  
  /**
   * Virtual thread executor for handling I/O-bound operations asynchronously.
   * Java 21 Virtual Threads are lightweight and efficient for operations that may block,
   * such as logging and event dispatching, without consuming OS thread resources.
   */
  private final Executor virtualThreadExecutor;

  private final Logger auditLogger = LoggerFactory.getLogger("auditlog");

  private volatile boolean enabled = false;

  /**
   * Constructor with required dependencies.
   * Initializes a virtual thread executor for handling I/O operations efficiently.
   * 
   * @param eventManager Manager for posting audit events
   * @param nodeAccess Provider of node information
   * @param initiatorProvider Provider of initiator information
   */
  @Inject
  public AuditRecorderImpl(
      final EventManager eventManager,
      final NodeAccess nodeAccess,
      final InitiatorProvider initiatorProvider)
  {
    this.eventManager = eventManager;
    this.nodeAccess = nodeAccess;
    this.initiatorProvider = initiatorProvider;
    
    // Initialize the virtual thread executor using Java 21's newVirtualThreadPerTaskExecutor
    // This creates a new virtual thread for each submitted task without thread pooling
    // Virtual threads are lightweight and efficient for I/O-bound operations
    this.virtualThreadExecutor = newVirtualThreadPerTaskExecutor();
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Records audit data if auditing is enabled.
   * 
   * This implementation leverages Java 21 features:
   * - Virtual Threads for non-blocking I/O operations (logging and event posting)
   * - String Templates for more readable and efficient logging
   * 
   * @param data The audit data to record, must not be null
   */
  @Override
  public void record(final AuditData data) {
    requireNonNull(data);

    if (enabled) {
      // fill in timestamp, node-id and initiator if missing
      if (data.getTimestamp() == null) {
        data.setTimestamp(new Date());
      }
      if (data.getNodeId() == null) {
        data.setNodeId(nodeAccess.getId());
      }
      if (data.getInitiator() == null) {
        String initiator = initiatorProvider.get();
        if (initiator.contains(UserIdHelper.UNKNOWN)) {
          setInitiator(data, initiator);
        }
        else {
          data.setInitiator(initiator);
        }
      }

      // Use virtual threads for I/O-bound operations (logging and event posting)
      // Virtual threads are lightweight and managed by the JVM, allowing for high concurrency
      // without the overhead of traditional platform threads
      virtualThreadExecutor.execute(() -> {
        try {
          // Use String Templates for improved logging readability and security
          // String Templates in Java 21 provide safer string interpolation than concatenation
          auditLogger.info(AUDIT_LOG_ONLY, STR."Audit event: \{new AuditDTO(data)}");

          // Post event using virtual thread to avoid blocking the caller
          eventManager.post(new AuditDataRecordedEvent(data));
        }
        catch (Exception e) {
          // Use String Templates for exception logging
          log.warn(STR."Failed to record audit data: \{e.getMessage()}", e);
        }
      });
    }
  }

  /**
   * Sets the initiator in the audit data by replacing the UNKNOWN placeholder with the principal value
   * if available in the attributes.
   * 
   * @param data The audit data to update
   * @param initiator The initiator string that may contain the UNKNOWN placeholder
   */
  private void setInitiator(final AuditData data, final String initiator) {
    if (data.getAttributes().containsKey("principal")) {
      // Safely replace the UNKNOWN placeholder with the principal value
      String newInitiator = initiator.replace(UserIdHelper.UNKNOWN, 
          data.getAttributes().get("principal").toString());
      data.setInitiator(newInitiator);
    }
    else {
      data.setInitiator(initiator);
    }
  }
}