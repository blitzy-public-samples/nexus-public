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

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response.Status;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.rest.Resource;
import org.sonatype.nexus.rest.ValidationErrorsException;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.NoSuchAuthorizationManagerException;
import org.sonatype.nexus.security.config.AdminPasswordFileManager;
import org.sonatype.nexus.security.internal.RealmToSource;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.NoSuchUserManagerException;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserManager;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserSearchCriteria;
import org.sonatype.nexus.validation.Validate;

import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang.StringUtils;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Resource for REST API to perform operations on the user.
 * Implements Virtual Threads for improved performance on I/O-bound operations.
 *
 * @since 3.17
 */
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class UserApiResource
    extends ComponentSupport
    implements Resource, UserApiResourceDoc
{
  public static final String ADMIN_USER_ID = "admin";
  private static final String SAML_SOURCE = "SAML";

  private final SecuritySystem securitySystem;
  private final AdminPasswordFileManager adminPasswordFileManager;

  @Inject
  public UserApiResource(final SecuritySystem securitySystem,
                         final AdminPasswordFileManager adminPasswordFileManager) {
    this.securitySystem = checkNotNull(securitySystem);
    this.adminPasswordFileManager = checkNotNull(adminPasswordFileManager);
  }

  @Override
  @GET
  @RequiresAuthentication
  @RequiresPermissions("nexus:users:read")
  public Collection<ApiUser> getUsers(
      @QueryParam("userId") final String userId,
      @QueryParam("source") final String source)
  {
    UserSearchCriteria criteria = new UserSearchCriteria(userId, null, source);

    if (!UserManager.DEFAULT_SOURCE.equals(source)) {
      // we limit the number of users here to avoid issues with remote sources
      criteria.setLimit(100);
    }

    // Using virtual thread for I/O-bound operation
    CompletableFuture<Collection<ApiUser>> future = CompletableFuture.supplyAsync(() -> {
      try {
        return securitySystem.searchUsers(criteria).stream()
            .map(this::fromUser)
            .collect(Collectors.toList());
      } catch (Exception e) {
        log.error(STR."Error searching users with criteria: \{criteria}", e);
        throw e;
      }
    }, runnable -> Thread.startVirtualThread(runnable));

    try {
      return future.join();
    } catch (Exception e) {
      log.error("Failed to retrieve users", e);
      throw new WebApplicationMessageException(
          Status.INTERNAL_SERVER_ERROR, 
          STR."\"Failed to retrieve users: \{e.getMessage()}\"", 
          MediaType.APPLICATION_JSON
      );
    }
  }

  @Override
  @POST
  @RequiresAuthentication
  @RequiresPermissions("nexus:users:create")
  @Validate
  public ApiUser createUser(final ApiCreateUser createUser) {
    if (Strings2.isBlank(createUser.getPassword())) {
      throw createWebException(Status.BAD_REQUEST, "A non-empty password is required.");
    }
    
    // Using virtual thread for I/O-bound operation
    CompletableFuture<ApiUser> future = CompletableFuture.supplyAsync(() -> {
      try {
        User user = securitySystem.addUser(createUser.toUser(), createUser.getPassword());
        return fromUser(user);
      } catch (NoSuchUserManagerException e) {
        log.error(STR."Unable to locate default usermanager for user: \{createUser.getUserId()}", e);
        throw createNoSuchUserManagerException(UserManager.DEFAULT_SOURCE);
      } catch (Exception e) {
        log.error(STR."Error creating user: \{createUser.getUserId()}", e);
        throw e;
      }
    }, runnable -> Thread.startVirtualThread(runnable));

    try {
      return future.join();
    } catch (WebApplicationMessageException e) {
      throw e; // Re-throw our custom exceptions
    } catch (Exception e) {
      log.error("Failed to create user", e);
      throw new WebApplicationMessageException(
          Status.INTERNAL_SERVER_ERROR, 
          STR."\"Failed to create user: \{e.getMessage()}\"", 
          MediaType.APPLICATION_JSON
      );
    }
  }

  @Override
  @PUT
  @Path("{userId}")
  @RequiresAuthentication
  @RequiresPermissions("nexus:users:update")
  @Validate
  public void updateUser(@PathParam("userId") final String userId, final ApiUser apiUser) {
    if (!userId.equals(apiUser.getUserId())) {
      log.debug("The path userId '{}' does not match the userId supplied in the body '{}'.", userId,
          apiUser.getUserId());
      throw createWebException(Status.BAD_REQUEST, "The path's userId does not match the body");
    }

    // Using virtual thread for I/O-bound operation
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        validateRoles(apiUser.getRoles());

        switch (apiUser.getSource()) {
          case UserManager.DEFAULT_SOURCE -> securitySystem.updateUser(apiUser.toUser());
          default -> {
            // Ensure user exists
            securitySystem.getUser(userId, apiUser.getSource());

            Set<RoleIdentifier> roleIdentifiers = apiUser.getRoles().stream()
                .map(roleId -> new RoleIdentifier(UserManager.DEFAULT_SOURCE, roleId))
                .collect(Collectors.toSet());
            securitySystem.setUsersRoles(userId, apiUser.getSource(), roleIdentifiers);
          }
        }
      } catch (UserNotFoundException e) {
        log.debug(STR."Unable to locate userId: \{userId}", e);
        throw createUnknownUserException(userId);
      } catch (NoSuchUserManagerException e) {
        log.debug(STR."Unable to locate source: \{apiUser.getSource()} for userId: \{userId}", e);
        throw createNoSuchUserManagerException(apiUser.getSource());
      } catch (Exception e) {
        log.error(STR."Error updating user: \{userId}", e);
        throw e;
      }
    }, runnable -> Thread.startVirtualThread(runnable));

    try {
      future.join();
    } catch (WebApplicationMessageException e) {
      throw e; // Re-throw our custom exceptions
    } catch (Exception e) {
      log.error("Failed to update user", e);
      throw new WebApplicationMessageException(
          Status.INTERNAL_SERVER_ERROR, 
          STR."\"Failed to update user: \{e.getMessage()}\"", 
          MediaType.APPLICATION_JSON
      );
    }
  }

  @Override
  @DELETE
  @Path("{userId}")
  @RequiresAuthentication
  @RequiresPermissions("nexus:users:delete")
  public void deleteUser(@PathParam("userId") final String userId,
                         @QueryParam("realm") final String realm) {
    // Using virtual thread for I/O-bound operation
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      User user = null;
      try {
        if (realm == null) {
          user = securitySystem.getUser(userId);
          if (!UserManager.DEFAULT_SOURCE.equals(user.getSource()) && !SAML_SOURCE.equals(user.getSource())) {
            throw createWebException(Status.BAD_REQUEST, "Non-local user cannot be deleted.");
          }
        } else {
          if (!securitySystem.isValidRealm(realm)) {
            throw createWebException(Status.BAD_REQUEST, "Invalid or empty realm name.");
          }
          else {
            user = securitySystem.getUser(userId, RealmToSource.getSource(realm));
          }
        }

        securitySystem.deleteUser(userId, user.getSource());
      }
      catch (NoSuchUserManagerException e) {
        // this should never actually happen
        String source = user != null && user.getSource() != null ? user.getSource() : "";
        log.error(STR."Unable to locate source: \{source} for userId: \{userId}", e);
        throw createNoSuchUserManagerException(source);
      }
      catch (UserNotFoundException e) {
        log.debug(STR."Unable to locate userId: \{userId}", e);
        throw createUnknownUserException(userId);
      }
      catch (Exception e) {
        log.error(STR."Error deleting user: \{userId}", e);
        throw e;
      }
    }, runnable -> Thread.startVirtualThread(runnable));

    try {
      future.join();
    } catch (WebApplicationMessageException e) {
      throw e; // Re-throw our custom exceptions
    } catch (Exception e) {
      log.error("Failed to delete user", e);
      throw new WebApplicationMessageException(
          Status.INTERNAL_SERVER_ERROR, 
          STR."\"Failed to delete user: \{e.getMessage()}\"", 
          MediaType.APPLICATION_JSON
      );
    }
  }

  @Override
  @PUT
  @RequiresAuthentication
  @RequiresPermissions("nexus:*")
  @Path("{userId}/change-password")
  @Consumes(MediaType.TEXT_PLAIN)
  @Validate
  public void changePassword(@PathParam("userId") final String userId, final String password) {
    if (StringUtils.isBlank(password)) {
      throw createWebException(Status.BAD_REQUEST, "Password must be supplied.");
    }

    // Using virtual thread for I/O-bound operation
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        securitySystem.changePassword(userId, password);

        // Ensure proper thread context management when using AdminPasswordFileManager
        if (ADMIN_USER_ID.equals(userId)) {
          adminPasswordFileManager.removeFile();
        }
      }
      catch (UserNotFoundException e) { // NOSONAR
        log.debug(STR."Request to change password for invalid user '\{userId}'.");
        throw createUnknownUserException(userId);
      }
      catch (Exception e) {
        log.error(STR."Error changing password for user: \{userId}", e);
        throw e;
      }
    }, runnable -> Thread.startVirtualThread(runnable));

    try {
      future.join();
    } catch (WebApplicationMessageException e) {
      throw e; // Re-throw our custom exceptions
    } catch (Exception e) {
      log.error("Failed to change password", e);
      throw new WebApplicationMessageException(
          Status.INTERNAL_SERVER_ERROR, 
          STR."\"Failed to change password: \{e.getMessage()}\"", 
          MediaType.APPLICATION_JSON
      );
    }
  }

  private boolean isReadOnly(final User user) {
    try {
      return !securitySystem.getUserManager(user.getSource()).supportsWrite();
    }
    catch (NoSuchUserManagerException e) {
      log.debug(STR."Unable to locate user manager: \{user.getSource()}", e);
      return true;
    }
  }

  @VisibleForTesting
  ApiUser fromUser(final User user) {
    Predicate<RoleIdentifier> isLocal = r -> UserManager.DEFAULT_SOURCE.equals(r.getSource());

    Set<String> internalRoles =
        user.getRoles().stream().filter(isLocal).map(RoleIdentifier::getRoleId).collect(Collectors.toSet());
    Set<String> externalRoles =
        user.getRoles().stream().filter(isLocal.negate()).map(RoleIdentifier::getRoleId).collect(Collectors.toSet());

    return new ApiUser(user.getUserId(), user.getFirstName(), user.getLastName(), user.getEmailAddress(),
        user.getSource(), ApiUserStatus.convert(user.getStatus()), isReadOnly(user), internalRoles, externalRoles);
  }

  private void validateRoles(final Set<String> roleIds) {
    ValidationErrorsException errors = new ValidationErrorsException();

    try {
      Set<String> localRoles = securitySystem.listRoles(UserManager.DEFAULT_SOURCE).stream()
          .map(Role::getRoleId)
          .collect(Collectors.toSet());
          
      // Using enhanced for loop for better readability with pattern matching
      for (String roleId : roleIds) {
        if (!localRoles.contains(roleId)) {
          errors.withError("roles", STR."Unable to locate roleId: \{roleId}");
        }
      }
      
      if (errors.hasValidationErrors()) {
        throw errors;
      }
    }
    catch (NoSuchAuthorizationManagerException e) {
      log.error("Unable to locate default user manager", e);
      throw createWebException(Status.INTERNAL_SERVER_ERROR, "Unable to locate default user manager");
    }
  }

  private WebApplicationMessageException createNoSuchUserManagerException(final String source) {
    return createWebException(Status.NOT_FOUND, STR."Unable to locate source: \{source}");
  }
  
  private WebApplicationMessageException createUnknownUserException(final String userId) {
    return createWebException(Status.NOT_FOUND, STR."User '\{userId}' not found.");
  }

  private WebApplicationMessageException createWebException(final Status status, final String message) {
    return new WebApplicationMessageException(status, STR."\"\{message}\"", MediaType.APPLICATION_JSON);
  }
}