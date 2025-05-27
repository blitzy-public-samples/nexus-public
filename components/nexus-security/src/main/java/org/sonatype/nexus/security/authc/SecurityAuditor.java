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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * Security auditor that records security events using virtual threads for asynchronous processing.
 * 
 * @since 3.60
 */
@Named
@Singleton
public class SecurityAuditor
    extends AuditorSupport
    implements EventAware
{
  /**
   * Virtual thread executor for processing audit events asynchronously.
   */
  private final Executor virtualThreadExecutor;

  public SecurityAuditor() {
    registerType(LoginEvent.class, "login");
    registerType(LogoutEvent.class, "logout");
    
    // Create a virtual thread per task executor for asynchronous audit processing
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Handles security events by processing them asynchronously using virtual threads.
   * 
   * @param event the security event to process
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final SecurityEvent event) {
    // Process the event asynchronously using a virtual thread
    virtualThreadExecutor.execute(() -> processSecurityEvent(event));
  }
  
  /**
   * Processes a security event by creating audit data and recording it.
   * Uses String Templates for consistent log message generation.
   * 
   * @param event the security event to process
   */
  private void processSecurityEvent(final SecurityEvent event) {
    if (!isRecording()) {
      return;
    }
    
    AuditData data = new AuditData();
    data.setDomain(event.getRealm());
    data.setType(type(event.getClass()));
    data.getAttributes().put("principal", event.getPrincipal());
    
    // Use String Templates for consistent log message generation
    String eventDescription = STR."Security event: \{type(event.getClass())} for principal \{event.getPrincipal()} in realm \{event.getRealm()}";
    data.getAttributes().put("description", eventDescription);
    
    record(data);
  }
}