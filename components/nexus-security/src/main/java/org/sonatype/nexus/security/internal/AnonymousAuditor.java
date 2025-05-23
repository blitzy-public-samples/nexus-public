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
package org.sonatype.nexus.security.internal;

import static java.lang.StringTemplate.STR;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousConfigurationChangedEvent;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * Anonymous auditor.
 *
 * @since 3.1
 */
@Named
@Singleton
public class AnonymousAuditor
    extends AuditorSupport
    implements EventAware
{
  public static final String DOMAIN = "security.anonymous";

  /**
   * Handles anonymous configuration change events.
   * 
   * This method is optimized for Java 21's execution model with Virtual Threads.
   * The @AllowConcurrentEvents annotation ensures compatibility with the new
   * concurrency model, allowing multiple events to be processed concurrently.
   *
   * @param event The configuration change event to process
   */
  @Subscribe
  @AllowConcurrentEvents
  public void on(final AnonymousConfigurationChangedEvent event) {
    if (isRecording()) {
      AnonymousConfiguration configuration = event.getConfiguration();

      // Create audit data using Java 21 String Templates for more readable logging
      AuditData data = new AuditData();
      data.setDomain(DOMAIN);
      data.setType(CHANGED_TYPE);
      data.setContext(SYSTEM_CONTEXT);

      // Use Java 21 String Templates for more readable and maintainable code
      // when logging the audit information
      log.debug(STR."Recording anonymous configuration change: enabled=\{configuration.isEnabled()}, userId=\{configuration.getUserId()}, realm=\{configuration.getRealmName()}");
      
      Map<String, Object> attributes = data.getAttributes();
      attributes.put("enabled", string(configuration.isEnabled()));
      attributes.put("userId", configuration.getUserId());
      attributes.put("realm", configuration.getRealmName());

      record(data);
    }
  }
}