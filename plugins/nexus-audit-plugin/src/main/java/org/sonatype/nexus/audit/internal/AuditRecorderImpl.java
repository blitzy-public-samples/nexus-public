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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.AUDIT_LOG_ONLY;

/**
 * Default {@link AuditRecorder} implementation.
 * 
 * Updated for Java 21 to leverage Virtual Threads for I/O operations and event handling,
 * improving scalability and performance for audit event recording.
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

  private final Logger auditLogger = LoggerFactory.getLogger("auditlog");
  
  /**
   * Virtual thread executor for handling I/O-bound audit operations asynchronously.
   * Java 21 Virtual Threads provide lightweight concurrency with minimal overhead,
   * making them ideal for I/O operations like logging and event dispatching.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  private volatile boolean enabled = false;

  @Inject
  public AuditRecorderImpl(
      final EventManager eventManager,
      final NodeAccess nodeAccess,
      final InitiatorProvider initiatorProvider)
  {
    this.eventManager = eventManager;
    this.nodeAccess = nodeAccess;
    this.initiatorProvider = initiatorProvider;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public void record(final AuditData data) {
    checkNotNull(data);

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

      // Create a final copy of the data for use in the virtual thread
      final AuditData finalData = data;
      
      // Use Virtual Threads for I/O-bound operations (logging and event posting)
      // This improves scalability by not blocking platform threads during I/O operations
      virtualThreadExecutor.submit(() -> {
        try {
          // Log the audit data
          auditLogger.info(AUDIT_LOG_ONLY, new AuditDTO(finalData).toString());
          
          // Post the event to the event manager
          eventManager.post(new AuditDataRecordedEvent(finalData));
          
          if (log.isDebugEnabled()) {
            log.debug("Audit event recorded successfully: domain={}, type={}", 
                finalData.getDomain(), finalData.getType());
          }
        }
        catch (Exception e) {
          log.warn("Failed to record audit data: {}", e.getMessage(), e);
        }
      });
    }
  }

  /**
   * Sets the initiator for the audit data, replacing the UNKNOWN placeholder with the principal
   * from attributes if available.
   * 
   * @param data The audit data to update
   * @param initiator The initiator string that may contain UNKNOWN placeholder
   */
  private void setInitiator(final AuditData data, final String initiator) {
    if (data.getAttributes().containsKey("principal")) {
      String newInitiator = initiator.replace(UserIdHelper.UNKNOWN, data.getAttributes().get("principal").toString());
      data.setInitiator(newInitiator);
    }
    else {
      data.setInitiator(initiator);
    }
  }
  
  /**
   * Safely shuts down the virtual thread executor when the component is stopped.
   * This ensures proper cleanup of resources and completion of pending tasks.
   */
  @Override
  protected void doStop() throws Exception {
    virtualThreadExecutor.close();
    super.doStop();
  }
}