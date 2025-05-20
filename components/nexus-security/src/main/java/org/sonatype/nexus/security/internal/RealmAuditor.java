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

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import static java.lang.StringTemplate.STR;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.security.realm.RealmConfigurationChangedEvent;
import org.sonatype.nexus.security.realm.RealmManager;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * Realm auditor.
 *
 * @since 3.1
 */
@Named
@Singleton
public class RealmAuditor
    extends AuditorSupport
    implements EventAware
{
  public static final String DOMAIN = "security.realm";

  private final RealmManager realmManager;

  @Inject
  public RealmAuditor(final RealmManager realmManager) {
    this.realmManager = realmManager;
  }

  @Subscribe
  @AllowConcurrentEvents
  public void on(final RealmConfigurationChangedEvent event) {
    if (isRecording()) {
      // Use Java 21 Virtual Threads for concurrent event processing
      Thread.startVirtualThread(() -> {
        // Create audit data with Java 21 compatibility
        AuditData data = new AuditData();
        data.setDomain(DOMAIN);
        data.setType(CHANGED_TYPE);
        data.setContext(SYSTEM_CONTEXT);

        // Add attributes using Java 21 String Templates for improved readability
        Map<String, Object> attributes = data.getAttributes();
        var configuredRealmIds = realmManager.getConfiguredRealmIds();
        
        // Log using Java 21 String Templates for improved readability
        log.debug(STR."Recording realm configuration change: \{string(configuredRealmIds)}");
        
        // Store the original string representation for compatibility
        attributes.put("realms", string(configuredRealmIds));

        // Record the audit data
        record(data);
      });
    }
  }
}