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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.audit.AuditData;
import org.sonatype.nexus.audit.AuditorSupport;
import org.sonatype.nexus.common.event.EventAware;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserCreatedEvent;
import org.sonatype.nexus.security.user.UserDeletedEvent;
import org.sonatype.nexus.security.user.UserEvent;
import org.sonatype.nexus.security.user.UserUpdatedEvent;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * {@link User} auditor.
 *
 * @since 3.1
 */
@Named
@Singleton
public class UserAuditor
    extends AuditorSupport
    implements EventAware
{
  public static final String DOMAIN = "security.user";

  public UserAuditor() {
    registerType(UserCreatedEvent.class, CREATED_TYPE);
    registerType(UserDeletedEvent.class, DELETED_TYPE);
    registerType(UserUpdatedEvent.class, UPDATED_TYPE);
  }

  @Subscribe
  @AllowConcurrentEvents
  public void on(final UserEvent event) {
    if (isRecording()) {
      User user = event.getUser();

      AuditData data = new AuditData();
      data.setDomain(DOMAIN);
      data.setType(type(event.getClass()));
      data.setContext(user.getUserId());

      Map<String, Object> attributes = data.getAttributes();
      
      // Using record pattern to simplify user data handling
      if (user instanceof User(var userId, var name, var email, var source, var status, var roles)) {
        // Using String Templates for improved readability in audit logging
        attributes.put("id", userId);
        attributes.put("name", name);
        attributes.put("email", email);
        attributes.put("source", source);
        attributes.put("status", STR."{status.name()}");
        attributes.put("roles", roles(roles));
      } else {
        // Fallback for non-record pattern support
        attributes.put("id", user.getUserId());
        attributes.put("name", user.getName());
        attributes.put("email", user.getEmailAddress());
        attributes.put("source", user.getSource());
        attributes.put("status", user.getStatus().name());
        attributes.put("roles", roles(user.getRoles()));
      }

      record(data);
    }
  }

  private static String roles(final List<RoleIdentifier> roles) {
    List<String> result = new ArrayList<>();

    for (RoleIdentifier role : roles) {
      // Using Record Patterns with String Templates for improved readability
      if (role instanceof RoleIdentifier(var roleId, var source)) {
        // Using String Templates with record pattern extracted variables
        result.add(STR."\{roleId} (\{source})");
      } else {
        // Fallback for non-record pattern support
        result.add(STR."\{role.getRoleId()} (\{role.getSource()})");
      }
    }

    return string(result);
  }
}