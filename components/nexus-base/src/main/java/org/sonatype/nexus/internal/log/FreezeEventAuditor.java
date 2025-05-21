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

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.freeze.event.FreezeForceReleaseEvent;
import org.sonatype.nexus.freeze.event.FreezeEvent;
import org.sonatype.nexus.freeze.event.FreezeRequestEvent;
import org.sonatype.nexus.freeze.event.FreezeReleaseEvent;

import static java.lang.StringTemplate.STR;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

@Named
@Singleton
public class FreezeEventAuditor
    extends AuditorSupport
    implements EventAware
{
  public static final String DOMAIN = "freeze";

  public FreezeEventAuditor() {
    registerType(FreezeRequestEvent.class, "freeze");
    registerType(FreezeReleaseEvent.class, "release");
    registerType(FreezeForceReleaseEvent.class, "forceRelease");
  }

  @Subscribe
  @AllowConcurrentEvents
  public void on(final FreezeEvent event) {
    if (isRecording()) {
      AuditData data = new AuditData();
      data.setDomain(DOMAIN);
      String eventType = type(event.getClass());
      data.setType(eventType);
      
      // Using pattern matching for instanceof with enhanced context using String Templates
      if (event instanceof FreezeRequestEvent requestEvent) {
        String reason = requestEvent.getReason();
        data.setContext(STR."System freeze requested with reason: \{reason}");
      } else if (event instanceof FreezeReleaseEvent releaseEvent) {
        data.setContext(STR."System freeze released normally");
      } else if (event instanceof FreezeForceReleaseEvent forceReleaseEvent) {
        data.setContext(STR."System freeze forcibly released");
      } else {
        data.setContext(STR."Unknown freeze event type: \{eventType}");
      }

      record(data);
    }
  }
}