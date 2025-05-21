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
package org.sonatype.nexus.internal.security.model;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.security.config.CRole;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.CUserRoleMapping;
import org.sonatype.nexus.security.config.SecurityConfiguration;
import org.sonatype.nexus.supportzip.ExportSecurityData;
import org.sonatype.nexus.supportzip.ImportData;
import org.sonatype.nexus.supportzip.datastore.JsonExporter;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import static java.util.function.Function.identity;
import static org.sonatype.nexus.security.user.UserManager.DEFAULT_SOURCE;

/**
 * Write/Read {@link CRole} data to/from a JSON file.
 * Updated for Java 21 with Virtual Threads support for I/O operations.
 *
 * @since 3.29
 */
@Named("securityUserExport")
@Singleton
public class SecurityUserExport
    extends JsonExporter
    implements ExportSecurityData, ImportData
{
  private final SecurityConfiguration configuration;
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public SecurityUserExport(final SecurityConfiguration configuration) {
    this.configuration = configuration;
    // Create a virtual thread per task executor for I/O operations
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public void export(final File file) throws IOException {
    log.debug("Export CUser and CUserRoleMapping data to {}", file);
    Map<String, CUser> userIdToCUser = configuration.getUsers()
        .stream()
        .collect(Collectors.toMap(CUser::getId, identity()));
    List<CUserRoleMapping> userRoleMappings = configuration.getUserRoleMappings();
    List<SecurityUserData> securityUsers = new ArrayList<>(userIdToCUser.size());
    
    // Process each user entry using Virtual Threads for better concurrency
    List<Future<?>> futures = new ArrayList<>();
    for (Entry<String, CUser> userEntry : userIdToCUser.entrySet()) {
      futures.add(virtualThreadExecutor.submit(() -> {
        List<CUserRoleMapping> roleMappings = userRoleMappings.stream()
            .filter(user -> user.getUserId().equals(userEntry.getKey()))
            .collect(Collectors.toList());
        SecurityUserData securityUserData = new SecurityUserData();
        securityUserData.setUser(userEntry.getValue());
        securityUserData.setUserRoleMappings(roleMappings);
        synchronized (securityUsers) {
          securityUsers.add(securityUserData);
        }
        return null;
      }));
    }
    
    // Wait for all processing to complete
    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (Exception e) {
        throw new IOException("Error processing user data", e);
      }
    }

    // Use a virtual thread for the I/O-intensive JSON export operation
    try {
      Future<Void> exportFuture = virtualThreadExecutor.submit(() -> {
        try {
          exportToJson(securityUsers, file);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return null;
      });
      exportFuture.get(); // Wait for export to complete
    } catch (Exception e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException("Error exporting user data to JSON", e);
    }
  }

  @Override
  public void restore(final File file) throws IOException {
    log.debug("Restoring CUser and CUserRoleMapping data from {}", file);
    
    // Use a virtual thread for the I/O-intensive JSON import operation
    List<SecurityUserData> securityUsers;
    try {
      Future<List<SecurityUserData>> importFuture = virtualThreadExecutor.submit(() -> {
        try {
          return importFromJson(file, SecurityUserData.class);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      securityUsers = importFuture.get(); // Wait for import to complete
    } catch (Exception e) {
      if (e.getCause() instanceof IOException) {
        throw (IOException) e.getCause();
      }
      throw new IOException("Error importing user data from JSON", e);
    }
    
    // Process each user using Virtual Threads for better concurrency
    List<Future<?>> futures = new ArrayList<>();
    for (SecurityUserData securityUser : securityUsers) {
      futures.add(virtualThreadExecutor.submit(() -> {
        configuration.addUser(securityUser.user, securityUser.getRoles());
        return null;
      }));
    }
    
    // Wait for all processing to complete
    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (Exception e) {
        throw new IOException("Error restoring user data", e);
      }
    }
  }

  /**
   * Data transfer object for security user information.
   * Updated with Java 21 compatible annotations for serialization/deserialization.
   */
  public static class SecurityUserData
  {
    @JsonProperty
    @JsonDeserialize(as = CUserData.class)
    private CUser user;

    @JsonProperty
    @JsonDeserialize(contentAs = CUserRoleMappingData.class)
    private List<CUserRoleMapping> userRoleMappings;

    public CUser getUser() {
      return user;
    }

    public void setUser(final CUser user) {
      this.user = user;
    }

    public List<CUserRoleMapping> getUserRoleMappings() {
      return userRoleMappings;
    }

    public void setUserRoleMappings(final List<CUserRoleMapping> userRoleMappings) {
      this.userRoleMappings = userRoleMappings;
    }

    @JsonIgnore
    public Set<String> getRoles() {
      return userRoleMappings.stream()
          .filter(role -> DEFAULT_SOURCE.equals(role.getSource()))
          .findFirst()
          .map(CUserRoleMapping::getRoles)
          .orElse(Collections.emptySet());
    }
  }
}