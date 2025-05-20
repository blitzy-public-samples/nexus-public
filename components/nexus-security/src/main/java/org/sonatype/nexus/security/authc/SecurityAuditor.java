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
package org.sonatype.nexus.security.authc;

import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.common.event.EventManager;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * Security auditor that records authentication events.
 * 
 * This implementation leverages Java 21 Virtual Threads for asynchronous audit event processing
 * and String Templates for consistent log message generation.
 * 
 * @since 3.0
 */
@Named
@Singleton
public class SecurityAuditor
    extends AuditorSupport
    implements EventAware
{
  private final EventManager eventManager;

  @Inject
  public SecurityAuditor(EventManager eventManager) {
    this.eventManager = eventManager;
    registerType(LoginEvent.class, "login");
    registerType(LogoutEvent.class, "logout");
  }

  /**
   * Handles security events by recording them in the audit log.
   * 
   * This method is annotated with @AllowConcurrentEvents to enable concurrent processing
   * which works well with Java 21 Virtual Threads for high-throughput event handling.
   * 
   * @param event The security event to be audited
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final SecurityEvent event) {
    if (!isRecording()) {
      return;
    }
    
    // Create audit data using String Templates for consistent formatting
    AuditData data = new AuditData();
    data.setDomain(event.realm());
    data.setType(type(event.getClass()));
    
    // Use String Templates for attribute values
    String principalInfo = STR."User \{event.principal()} in realm \{event.realm()}";
    data.getAttributes().put("principal", event.principal());
    data.getAttributes().put("info", principalInfo);
    
    // Process audit asynchronously using Virtual Threads
    if (eventManager.isVirtualThreadsEnabled()) {
      CompletableFuture.runAsync(() -> record(data));
    } else {
      record(data);
    }
  }
  
  /**
   * Processes a security event asynchronously using Java 21 Virtual Threads.
   * This method provides an alternative way to handle events outside the EventBus.
   * 
   * @param event The security event to process
   * @return A CompletableFuture that completes when the event is processed
   */
  public CompletableFuture<Void> processAsync(final SecurityEvent event) {
    return eventManager.postAsync(event);
  }
}
