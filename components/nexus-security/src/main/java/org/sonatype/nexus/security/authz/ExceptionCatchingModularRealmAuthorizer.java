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
package org.sonatype.nexus.security.authz;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Provider;

import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.authz.Authorizer;
import org.apache.shiro.authz.ModularRealmAuthorizer;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.permission.RolePermissionResolver;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.PrincipalCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A implementation of the Shiro ModularRealmAuthorizer, that catches exceptions caused by individual realms and
 * ignores them. For example if a JDBC realm throws an exception while getting the list of users Roles (and is not caught, the
 * system should continue looking for permissions in other realms).
 * 
 * This implementation has been updated for Java 21 to use pattern matching for exceptions, Virtual Threads for concurrent
 * realm evaluation, and String Templates for more readable log messages.
 */
public class ExceptionCatchingModularRealmAuthorizer
    extends ModularRealmAuthorizer
{
  private static final Logger logger = LoggerFactory.getLogger(ExceptionCatchingModularRealmAuthorizer.class);

  private Provider<RolePermissionResolver> rolePermissionResolverProvider;

  public ExceptionCatchingModularRealmAuthorizer(Collection<Realm> realms) {
    super(realms);
  }

  @Inject
  public ExceptionCatchingModularRealmAuthorizer(final Collection<Realm> realms, 
                                                 final Provider<RolePermissionResolver> rolePermissionResolverProvider)
  {
    this.rolePermissionResolverProvider = rolePermissionResolverProvider;
    setRealms(realms);
  }

  @Override
  public RolePermissionResolver getRolePermissionResolver() {
    return rolePermissionResolverProvider != null ? rolePermissionResolverProvider.get() : null;
  }

  // Authorization
  @Override
  public void checkPermission(PrincipalCollection subjectPrincipal, String permission) {
    if (!isPermitted(subjectPrincipal, permission)) {
      throw new AuthorizationException(STR."User is not permitted: \{permission}");
    }
  }

  @Override
  public void checkPermission(PrincipalCollection subjectPrincipal, Permission permission) {
    if (!isPermitted(subjectPrincipal, permission)) {
      throw new AuthorizationException(STR."User is not permitted: \{permission}");
    }
  }

  @Override
  public void checkPermissions(PrincipalCollection subjectPrincipal, String... permissions) {
    for (String permission : permissions) {
      checkPermission(subjectPrincipal, permission);
    }
  }

  @Override
  public void checkPermissions(PrincipalCollection subjectPrincipal, Collection<Permission> permissions) {
    for (Permission permission : permissions) {
      checkPermission(subjectPrincipal, permission);
    }
  }

  @Override
  public void checkRole(PrincipalCollection subjectPrincipal, String roleIdentifier) {
    if (!hasRole(subjectPrincipal, roleIdentifier)) {
      throw new AuthorizationException(STR."User is not permitted role: \{roleIdentifier}");
    }
  }

  @Override
  public void checkRoles(PrincipalCollection subjectPrincipal, Collection<String> roleIdentifiers) {
    if (!hasAllRoles(subjectPrincipal, roleIdentifiers)) {
      throw new AuthorizationException(STR."User is not permitted role: \{roleIdentifiers}");
    }
  }

  @Override
  public boolean hasAllRoles(PrincipalCollection subjectPrincipal, Collection<String> roleIdentifiers) {
    for (String roleIdentifier : roleIdentifiers) {
      if (!hasRole(subjectPrincipal, roleIdentifier)) {
        return false;
      }
    }

    return true;
  }

  @Override
  public boolean hasRole(PrincipalCollection subjectPrincipal, String roleIdentifier) {
    for (Realm realm : getRealms()) {
      if (!(realm instanceof Authorizer)) {
        continue; // ignore non-authorizing realms
      }
      // Using pattern matching with guards for exception handling
      try {
        if (((Authorizer) realm).hasRole(subjectPrincipal, roleIdentifier)) {
          return true;
        }
      }
      catch (Exception e) when (e instanceof AuthorizationException || e instanceof RuntimeException) {
        logAndIgnore(realm, e);
      }
    }

    return false;
  }

  @Override
  public boolean[] hasRoles(PrincipalCollection subjectPrincipal, List<String> roleIdentifiers) {
    boolean[] combinedResult = new boolean[roleIdentifiers.size()];

    for (Realm realm : getRealms()) {
      if (!(realm instanceof Authorizer)) {
        continue; // ignore non-authorizing realms
      }
      try {
        boolean[] result = ((Authorizer) realm).hasRoles(subjectPrincipal, roleIdentifiers);

        for (int i = 0; i < combinedResult.length; i++) {
          combinedResult[i] = combinedResult[i] | result[i];
        }
      }
      catch (Exception e) when (e instanceof AuthorizationException || e instanceof RuntimeException) {
        logAndIgnore(realm, e);
      }
    }

    return combinedResult;
  }

  @Override
  public boolean isPermitted(PrincipalCollection subjectPrincipal, String permission) {
    // Using Virtual Threads for concurrent realm evaluation
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Collection<Realm> realms = getRealms().stream()
          .filter(realm -> realm instanceof Authorizer)
          .collect(Collectors.toList());
      
      List<Future<Boolean>> futures = realms.stream()
          .map(realm -> executor.submit(() -> checkPermissionInRealm(realm, subjectPrincipal, permission)))
          .collect(Collectors.toList());
      
      // If any realm returns true, the permission is granted
      for (Future<Boolean> future : futures) {
        try {
          if (future.get()) {
            return true;
          }
        } catch (InterruptedException | ExecutionException e) {
          // Ignore exceptions from individual realm checks
          Thread.currentThread().interrupt();
        }
      }
      
      return false;
    }
  }

  private boolean checkPermissionInRealm(Realm realm, PrincipalCollection subjectPrincipal, String permission) {
    try {
      boolean permitted = ((Authorizer) realm).isPermitted(subjectPrincipal, permission);
      if (permitted && logger.isTraceEnabled()) {
        logger.trace(STR."Realm: \{realm.getName()} user: \{subjectPrincipal.iterator().next()} has permission: \{permission}");
      } else if (logger.isTraceEnabled()) {
        logger.trace(STR."Realm: \{realm.getName()} user: \{subjectPrincipal.iterator().next()} does NOT have permission: \{permission}");
      }
      return permitted;
    } catch (Exception e) when (e instanceof AuthorizationException || e instanceof RuntimeException) {
      logAndIgnore(realm, e);
      return false;
    }
  }

  @Override
  public boolean isPermitted(PrincipalCollection subjectPrincipal, Permission permission) {
    // Using Virtual Threads for concurrent realm evaluation
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Collection<Realm> realms = getRealms().stream()
          .filter(realm -> realm instanceof Authorizer)
          .collect(Collectors.toList());
      
      List<Future<Boolean>> futures = realms.stream()
          .map(realm -> executor.submit(() -> checkPermissionInRealm(realm, subjectPrincipal, permission)))
          .collect(Collectors.toList());
      
      // If any realm returns true, the permission is granted
      for (Future<Boolean> future : futures) {
        try {
          if (future.get()) {
            return true;
          }
        } catch (InterruptedException | ExecutionException e) {
          // Ignore exceptions from individual realm checks
          Thread.currentThread().interrupt();
        }
      }
      
      return false;
    }
  }

  private boolean checkPermissionInRealm(Realm realm, PrincipalCollection subjectPrincipal, Permission permission) {
    try {
      return ((Authorizer) realm).isPermitted(subjectPrincipal, permission);
    } catch (Exception e) when (e instanceof AuthorizationException || e instanceof RuntimeException) {
      logAndIgnore(realm, e);
      return false;
    }
  }

  @Override
  public boolean[] isPermitted(PrincipalCollection subjectPrincipal, String... permissions) {
    boolean[] combinedResult = new boolean[permissions.length];

    for (Realm realm : getRealms()) {
      if (!(realm instanceof Authorizer)) {
        continue; // ignore non-authorizing realms
      }
      try {
        boolean[] result = ((Authorizer) realm).isPermitted(subjectPrincipal, permissions);

        for (int i = 0; i < combinedResult.length; i++) {
          combinedResult[i] = combinedResult[i] | result[i];
        }
      }
      catch (Exception e) when (e instanceof AuthorizationException || e instanceof RuntimeException) {
        logAndIgnore(realm, e);
      }
    }

    return combinedResult;
  }

  @Override
  public boolean[] isPermitted(PrincipalCollection subjectPrincipal, List<Permission> permissions) {
    boolean[] combinedResult = new boolean[permissions.size()];

    for (Realm realm : getRealms()) {
      if (!(realm instanceof Authorizer)) {
        continue; // ignore non-authorizing realms
      }
      try {
        boolean[] result = ((Authorizer) realm).isPermitted(subjectPrincipal, permissions);

        for (int i = 0; i < combinedResult.length; i++) {
          combinedResult[i] = combinedResult[i] | result[i];
        }
      }
      catch (Exception e) when (e instanceof AuthorizationException || e instanceof RuntimeException) {
        logAndIgnore(realm, e);
      }
    }

    return combinedResult;
  }

  @Override
  public boolean isPermittedAll(PrincipalCollection subjectPrincipal, String... permissions) {
    for (String permission : permissions) {
      if (!isPermitted(subjectPrincipal, permission)) {
        return false;
      }
    }

    return true;
  }

  @Override
  public boolean isPermittedAll(PrincipalCollection subjectPrincipal, Collection<Permission> permissions) {
    for (Permission permission : permissions) {
      if (!isPermitted(subjectPrincipal, permission)) {
        return false;
      }
    }

    return true;
  }

  private void logAndIgnore(Realm realm, Exception e) {
    logger.trace(STR."Realm '\{realm.getName()}' failure", e);
  }
}