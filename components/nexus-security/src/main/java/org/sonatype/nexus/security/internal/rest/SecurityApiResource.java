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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.security.SecuritySystem;
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

  /**
   * Retrieves a list of available user sources using Java 21 Virtual Threads for improved performance.
   * This method filters out the ConfiguredUsersUserManager source and processes each UserManager
   * concurrently using Virtual Threads when appropriate.
   *
   * Virtual Threads are particularly effective for I/O-bound operations like retrieving user sources
   * from external systems or databases, which may be the case for some UserManager implementations.
   *
   * @return List of ApiUserSource objects representing available user sources
   */
  @Override
  @GET
  @Path("user-sources")
  @RequiresPermissions("nexus:users:read")
  public List<ApiUserSource> getUserSources() {
    try {
      // Capture the current Shiro subject to maintain security context in virtual threads
      Subject currentSubject = SecurityUtils.getSubject();
      
      // Filter user managers to exclude ConfiguredUsersUserManager.SOURCE
      List<UserManager> filteredManagers = userManagers.values().stream()
          .filter(um -> !ConfiguredUsersUserManager.SOURCE.equals(um.getSource()))
          .toList(); // Using Java 21's toList() for immutable list collection
      
      // For very small number of managers, it's more efficient to process directly
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
      
      // For multiple managers, use virtual threads for concurrent processing
      List<ApiUserSource> results = new ArrayList<>();
      
      // Use try-with-resources to ensure the executor is properly closed
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit tasks to process each UserManager concurrently
        List<Future<ApiUserSource>> futures = filteredManagers.stream()
            .map(um -> executor.submit(() -> {
              try {
                // Associate the security context with this virtual thread
                // This is critical for maintaining security in Shiro 1.13.0 with Virtual Threads
                SecurityUtils.setSubject(currentSubject);
                
                // Create the ApiUserSource - this could involve I/O operations
                // depending on the UserManager implementation
                return new ApiUserSource(um);
              } catch (Exception e) {
                log.error("Error processing UserManager {}", um.getSource(), e);
                return null;
              }
            }))
            .toList();
        
        // Collect results, filtering out any null values from failed tasks
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