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
package org.sonatype.nexus.internal.script;

import java.util.Map;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptCreatedEvent;
import org.sonatype.nexus.script.ScriptDeletedEvent;
import org.sonatype.nexus.script.ScriptEvent;
import org.sonatype.nexus.script.ScriptUpdatedEvent;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * {@link Script} auditor.
 *
 * @since 3.1
 */
@Named
@Singleton
public class ScriptAuditor
    extends AuditorSupport
    implements EventAware
{
  private static final Logger log = LoggerFactory.getLogger(ScriptAuditor.class);
  public static final String DOMAIN = "script";

  public ScriptAuditor() {
    registerType(ScriptCreatedEvent.class, CREATED_TYPE);
    registerType(ScriptDeletedEvent.class, DELETED_TYPE);
    registerType(ScriptUpdatedEvent.class, UPDATED_TYPE);
  }

  @Subscribe
  @AllowConcurrentEvents
  public void on(final ScriptEvent event) {
    if (isRecording()) {
      // Using pattern matching for switch with ScriptEvent types
      switch (event) {
        case ScriptCreatedEvent e -> processEvent(e.getScript(), type(ScriptCreatedEvent.class));
        case ScriptUpdatedEvent e -> processEvent(e.getScript(), type(ScriptUpdatedEvent.class));
        case ScriptDeletedEvent e -> processEvent(e.getScript(), type(ScriptDeletedEvent.class));
        default -> processEvent(event.getScript(), type(event.getClass()));
      }
    }
  }
  
  /**
   * Process a script event and record audit data.
   * Optimized for Virtual Threads by keeping operations lightweight.
   */
  private void processEvent(final Script script, final String eventType) {
    // Using Java 21 String Templates for enhanced logging
    String scriptName = script.getName();
    String scriptType = script.getType();
    String auditContext = STR."Script '{scriptName}' of type '{scriptType}'";
    
    AuditData data = new AuditData();
    data.setDomain(DOMAIN);
    data.setType(eventType);
    data.setContext(scriptName);

    Map<String, Object> attributes = data.getAttributes();
    attributes.put("name", scriptName);
    attributes.put("type", scriptType);

    // Log using String Templates before recording
    if (log.isDebugEnabled()) {
      log.debug(STR."Recording audit event: {eventType} for {auditContext}");
    }

    record(data);
  }
}