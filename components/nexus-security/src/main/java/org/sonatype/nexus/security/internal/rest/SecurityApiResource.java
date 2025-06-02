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

package org.sonatype.nexus.security.internal.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.security.user.ConfiguredUsersUserManager;
import org.sonatype.nexus.security.user.UserManager;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.subject.Subject;

/**
 * REST resource for security API operations.
 *
 * @since 3.17
 */
@Named
@Singleton
@RequiresAuthentication
@Produces(MediaType.APPLICATION_JSON)
public class SecurityApiResource
        extends ComponentSupport
        implements Resource, SecurityApiResourceDoc
{
  private final Map<String, UserManager> userManagers;

  @Inject
  public SecurityApiResource(final Map<String, UserManager> userManagers) {
    this.userManagers = userManagers;
  }

  @Override
  @GET
  @Path("user-sources")
  @RequiresPermissions("nexus:users:read")
  public List<ApiUserSource> getUserSources() {
    try {
      Subject currentSubject = SecurityUtils.getSubject();

      List<UserManager> filteredManagers = userManagers.values().stream()
              .filter(um -> !ConfiguredUsersUserManager.SOURCE.equals(um.getSource()))
              .toList();

      if (filteredManagers.size() <= 1) {
        return filteredManagers.stream()
                .map(um -> {
                  try {
                    return new ApiUserSource(um);
                  } catch (Exception e) {
                    log.error("Error processing UserManager {}", um.getSource(), e);
                    return null;
                  }
                })
                .filter(source -> source != null)
                .collect(Collectors.toList());
      }

      List<ApiUserSource> results = new ArrayList<>();

      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<ApiUserSource>> futures = filteredManagers.stream()
                .map(um -> executor.submit(() -> {
                  try {
                    Subject subject = currentSubject; // Maintain security context
                    return new ApiUserSource(um);
                  } catch (Exception e) {
                    log.error("Error processing UserManager {}", um.getSource(), e);
                    return null;
                  }
                }))
                .toList();

        for (Future<ApiUserSource> future : futures) {
          try {
            ApiUserSource source = future.get();
            if (source != null) {
              results.add(source);
            }
          } catch (Exception e) {
            log.error("Error retrieving user source", e);
          }
        }
      }

      return results;
    } catch (Exception e) {
      log.error("Unexpected error retrieving user sources", e);
      throw e;
    }
  }
}