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
package org.sonatype.nexus.coreui;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;

import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.apache.shiro.authz.annotation.RequiresUser;
import org.apache.shiro.subject.Subject;

import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.common.wonderland.AuthTicketService;
import org.sonatype.nexus.extdirect.DirectComponent;
import org.sonatype.nexus.extdirect.DirectComponentSupport;
import org.sonatype.nexus.extdirect.model.StoreLoadParameters;
import org.sonatype.nexus.rapture.PasswordPlaceholder;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.anonymous.AnonymousConfiguration;
import org.sonatype.nexus.security.anonymous.AnonymousManager;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.NoSuchUserManagerException;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserManager;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserSearchCriteria;
import org.sonatype.nexus.validation.Validate;
import org.sonatype.nexus.validation.group.Create;
import org.sonatype.nexus.validation.group.Update;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.google.inject.Key;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.eclipse.sisu.inject.BeanLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.StreamSupport.stream;
import static org.sonatype.nexus.security.user.UserManager.DEFAULT_SOURCE;

/**
 * User {@link DirectComponent}.
 */
@Named
@Singleton
@DirectAction(action = "coreui_User")
public class UserComponent
    extends DirectComponentSupport
{
  private static final Logger logger = LoggerFactory.getLogger(UserComponent.class);
	
  private final SecuritySystem securitySystem;

  private final AnonymousManager anonymousManager;

  private final AuthTicketService authTickets;

  private final BeanLocator beanLocator;
  
  private ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public UserComponent(
      final SecuritySystem securitySystem,
      final AnonymousManager anonymousManager,
      final AuthTicketService authTickets,
      final BeanLocator beanLocator)
  {
    this.securitySystem = checkNotNull(securitySystem);
    this.anonymousManager = checkNotNull(anonymousManager);
    this.authTickets = checkNotNull(authTickets);
    this.beanLocator = checkNotNull(beanLocator);
  }

  /**
   * Retrieve user by id.
   *
   * @return details of the requested userId
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:users:read")
  public UserXO get(final String userId, final String source) throws UserNotFoundException, NoSuchUserManagerException {
    return convert(securitySystem.getUser(userId, source));
  }

  /**
   * Retrieve users.
   *
   * @return a list of users
 * @throws Exception 
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:users:read")
  public List<UserXO> read(@Nullable final StoreLoadParameters parameters) throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(() -> {
        Optional<StoreLoadParameters> optParameters = Optional.ofNullable(parameters);
        String source = optParameters
            .map(p -> p.getFilter("source"))
            .orElse(DEFAULT_SOURCE);
        String userId = optParameters
            .map(p -> p.getFilter("userId"))
            .orElse(null);
        Integer limit = optParameters
            .map(StoreLoadParameters::getLimit)
            .orElse(null);

        UserSearchCriteria searchCriteria = new UserSearchCriteria();
        searchCriteria.setSource(source);
        searchCriteria.setUserId(userId);
        searchCriteria.setLimit(limit);

        return securitySystem.searchUsers(searchCriteria)
            .stream()
            .map(this::convert)
            .collect(Collectors.toList()); // NOSONAR
      }).get();
    } catch (Exception e) {
      log.error("Error retrieving users", e);
      throw e;
    }
  }

  /**
   * Retrieves available user sources.
   *
   * @return a list of available user sources (user managers)
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:users:read")
  public List<ReferenceXO> readSources() {
	try {
		Future<List<ReferenceXO>> future = executor.submit(() -> {
        return stream(beanLocator.locate(Key.get(UserManager.class, Named.class)).spliterator(), false)
          .map(entry -> new ReferenceXO(((Named) entry.getKey()).value(),
              Strings2.isBlank(entry.getDescription()) ? ((Named) entry.getKey()).value() : entry.getDescription()))
          .collect(Collectors.toList()); // NOSONAR
		});
		
		return future.get();
	}  catch (Exception e) {
		logger.error("Error retrieves available user sources.: ", e);
	}
    return Collections.emptyList();
  }

  /**
   * Creates a user.
   *
   * @param userXO to be created
   * @return created user
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:users:create")
  @Validate(groups = {Create.class, Default.class})
  public UserXO create(@NotNull @Valid final UserXO userXO) throws NoSuchUserManagerException {
	  try {
			Future<UserXO> future = executor.submit(() -> {
		        User user = new User();
		        user.setUserId(userXO.userId());
		        user.setSource(DEFAULT_SOURCE);
		        user.setFirstName(userXO.firstName());
		        user.setLastName(userXO.lastName());
		        user.setEmailAddress(userXO.email());
		        user.setStatus(userXO.status());
		        user.setRoles(getRoles(userXO));
		        return convert(securitySystem.addUser(user, userXO.password()));
		      });
			
			return future.get();
		}  catch (Exception e) {
			logger.error("Error while creating user: ", e);
		}
	  return null;
  }

  /**
   * Update a user.
   *
   * @param userXO to be updated
   * @return updated user
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:users:update")
  @Validate(groups = {Update.class, Default.class})
  public UserXO update(@NotNull @Valid final UserXO userXO) throws UserNotFoundException, NoSuchUserManagerException {
		try {
			Future<UserXO> future = executor.submit(() -> {
				User user = new User();
				user.setUserId(userXO.userId());
				user.setVersion(Integer.parseInt(userXO.version()));
				user.setSource(DEFAULT_SOURCE);
				user.setFirstName(userXO.firstName());
				user.setLastName(userXO.lastName());
				user.setEmailAddress(userXO.email());
				user.setStatus(userXO.status());
				user.setRoles(getRoles(userXO));
				return convert(securitySystem.updateUser(user));
			});

			return future.get();
		} catch (Exception e) {
			logger.error("Error while updating user: ", e);
		}
		return null;
  }

  /**
   * Update user role mappings.
   *
   * @param userRoleMappingsXO to be updated
   * @return updated user
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:users:update")
  @Validate(groups = {Update.class, Default.class})
  public UserXO updateRoleMappings(
      @NotNull @Valid final UserRoleMappingsXO userRoleMappingsXO) throws UserNotFoundException, NoSuchUserManagerException
  {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(() -> {
        Set<String> mappedRoles = userRoleMappingsXO.roles();
        if (mappedRoles != null && !mappedRoles.isEmpty()) {
          User user = securitySystem.getUser(userRoleMappingsXO.userId(), userRoleMappingsXO.realm());
          user.getRoles().forEach(role -> {
            if (role.getSource().equals(userRoleMappingsXO.realm())) {
              mappedRoles.remove(role.getRoleId());
            }
          });
        }
        securitySystem.setUsersRoles(userRoleMappingsXO.userId(), userRoleMappingsXO.realm(),
            mappedRoles != null && !mappedRoles.isEmpty()
                ? mappedRoles.stream()
                    .map(roleId -> new RoleIdentifier(DEFAULT_SOURCE, roleId))
                    .collect(Collectors.toSet())
                : null);
        return convert(securitySystem.getUser(userRoleMappingsXO.userId(), userRoleMappingsXO.realm()));
      }).get();
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt(); 
        System.err.println("Thread interrupted while getting content type: " + e.getMessage());
    }  catch (ExecutionException e) {
        Throwable cause = e.getCause(); // Get the original exception
        if (cause instanceof RuntimeException) {
            System.err.println("Error probing content type (from RuntimeException): " + cause.getMessage());
        } else if (cause instanceof Error) {
            System.err.println("Error occurred during content type probe: " + cause.getMessage());
            throw (Error) cause; // Re-throw Errors
        } else {
            System.err.println("Unexpected exception during content type probe: " + cause.getMessage());
        }
    }
    
    return null;
  }

  /**
   * Change password of a specified user.
   *
   * @param authToken authentication token
   * @param userId id of user to change password for
   * @param password new password
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresUser
  @RequiresAuthentication
  @RequiresPermissions("nexus:userschangepw:create")
  @Validate
  public void changePassword(
      @NotEmpty final String authToken,
      @NotEmpty final String userId,
      @NotEmpty final String password) throws Exception
  {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        if (authTickets.redeemTicket(authToken)) {
          if (isAnonymousUser(userId)) {
            throw new Exception(
                "Password cannot be changed for user " + userId + ", since is marked as the Anonymous user");
          }
          securitySystem.changePassword(userId, password);
        }
        else {
          throw new IllegalAccessException("Invalid authentication ticket");
        }
        return null;
      });
    } catch (Exception e) {
      log.error("Error changing password", e);
      throw e;
    }
  }

  /**
   * Deletes a user.
   *
   * @param id of user to be deleted
   * @param source of user to be deleted
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:users:delete")
  @Validate
  public void remove(@NotEmpty final String id, @NotEmpty final String source) throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        // TODO check if source is required or we always delete from default realm
        if (isAnonymousUser(id)) {
          throw new Exception("User " + id + " cannot be deleted, since is marked as the Anonymous user");
        }
        if (isCurrentUser(id)) {
          throw new Exception("User " + id + " cannot be deleted, since is the user currently logged into the application");
        }
        securitySystem.deleteUser(id, source);
        return null;
      });
    } catch (Exception e) {
      log.error("Error removing user", e);
      throw e;
    }
  }

  /**
   * Convert user to XO.
   */
  private UserXO convert(final User user) {
    UserXO userXO = new UserXO();
    userXO.setUserId(user.getUserId());
    userXO.setVersion(String.valueOf(user.getVersion()));
    userXO.setRealm(user.getSource());
    userXO.setFirstName(user.getFirstName());
    userXO.setLastName(user.getLastName());
    userXO.setEmail(user.getEmailAddress());
    userXO.setStatus(user.getStatus());
    userXO.setPassword(PasswordPlaceholder.get());
    userXO.setRoles(user.getRoles().stream().map(RoleIdentifier::getRoleId).collect(Collectors.toSet()));
    userXO.setExternal(!DEFAULT_SOURCE.equals(user.getSource()));
    if (Boolean.TRUE.equals(userXO.isExternal())) {
      userXO.setExternalRoles(user.getRoles()
          .stream()
          .filter(role -> !DEFAULT_SOURCE.equals(role.getSource()))
          .map(RoleIdentifier::getRoleId)
          .collect(Collectors.toSet()));
    }
    return userXO;
  }

  private boolean isAnonymousUser(String userId) {
    AnonymousConfiguration config = anonymousManager.getConfiguration();
    return config.isEnabled() && config.getUserId().equals(userId);
  }

  private boolean isCurrentUser(String userId) {
    Subject subject = securitySystem.getSubject();
    if (subject == null || subject.getPrincipal() == null) {
      return false;
    }
    return subject.getPrincipal().equals(userId);
  }

  private Set<RoleIdentifier> getRoles(UserXO userXO) {
    if (userXO.getRoles() == null) {
      return null;
    }
    return userXO.getRoles()
        .stream()
        .map(id -> new RoleIdentifier(DEFAULT_SOURCE, id))
        .collect(Collectors.toSet());
  }
}