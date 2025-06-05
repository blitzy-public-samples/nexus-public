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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;

import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.extdirect.DirectComponent;
import org.sonatype.nexus.extdirect.DirectComponentSupport;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.authz.NoSuchAuthorizationManagerException;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.validation.Validate;
import org.sonatype.nexus.validation.group.Create;
import org.sonatype.nexus.validation.group.Update;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import javax.validation.constraints.NotEmpty;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.security.user.UserManager.DEFAULT_SOURCE;

/**
 * Role {@link DirectComponent}.
 */
@Named
@Singleton
@DirectAction(action = "coreui_Role")
public class RoleComponent
    extends DirectComponentSupport
{
  private final SecuritySystem securitySystem;

  private final List<AuthorizationManager> authorizationManagers;
  
  // Create a virtual thread executor for I/O-bound operations
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public RoleComponent(final SecuritySystem securitySystem, final List<AuthorizationManager> authorizationManagers) {
    this.securitySystem = checkNotNull(securitySystem);
    this.authorizationManagers = checkNotNull(authorizationManagers);
  }

  /**
   * Retrieves roles from all available {@link AuthorizationManager}s.
   *
   * @return a list of roles
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:roles:read")
  public List<RoleXO> read() throws NoSuchAuthorizationManagerException {
    try {
      Future<List<RoleXO>> future = virtualThreadExecutor.submit(() -> 
          securitySystem.listRoles(DEFAULT_SOURCE)
              .stream()
              .map(this::convert)
              .collect(Collectors.toList()));
      return future.get();
    } catch (InterruptedException | ExecutionException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Error retrieving roles", e);
    }
  }

  /**
   * Retrieves role references from all available {@link AuthorizationManager}s.
   *
   * @return a list of role references
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:roles:read")
  public List<ReferenceXO> readReferences() throws NoSuchAuthorizationManagerException {
    try {
      Future<List<ReferenceXO>> future = virtualThreadExecutor.submit(() -> 
          securitySystem.listRoles(DEFAULT_SOURCE)
              .stream()
              .map(input -> new ReferenceXO(input.getRoleId(), input.getName()))
              .collect(Collectors.toList()));
      return future.get();
    } catch (InterruptedException | ExecutionException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Error retrieving role references", e);
    }
  }

  /**
   * Retrieves available role sources.
   *
   * @return list of sources
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  public List<ReferenceXO> readSources() {
    try {
      Future<List<ReferenceXO>> future = virtualThreadExecutor.submit(() -> 
          authorizationManagers.stream()
              .filter(manager -> !DEFAULT_SOURCE.equals(manager.getSource()))
              .map(manager -> new ReferenceXO(manager.getSource(), manager.getSource()))
              .collect(Collectors.toList()));
      return future.get();
    } catch (InterruptedException | ExecutionException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Error retrieving role sources", e);
    }
  }

  /**
   * Retrieves roles from specified source.
   *
   * @param source to retrieve roles from
   * @return a list of roles
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:roles:read")
  @Validate
  public List<RoleXO> readFromSource(@NotEmpty final String source) throws NoSuchAuthorizationManagerException {
    try {
      Future<List<RoleXO>> future = virtualThreadExecutor.submit(() -> 
          securitySystem.listRoles(source)
              .stream()
              .map(this::convert)
              .collect(Collectors.toList()));
      return future.get();
    } catch (InterruptedException | ExecutionException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Error retrieving roles from source: " + source, e);
    }
  }

  /**
   * Creates a role.
   *
   * @param roleXO to be created
   * @return created role
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:roles:create")
  @Validate(groups = {Create.class, Default.class})
  public RoleXO create(@NotNull @Valid final RoleXO roleXO) throws NoSuchAuthorizationManagerException {
    try {
      Future<RoleXO> future = virtualThreadExecutor.submit(() -> {
        // HACK: Temporary validation for external role IDs to support editable text entry in combo box (LDAP only)
        if ("LDAP".equals(roleXO.source())) {
          securitySystem.getAuthorizationManager(roleXO.source()).getRole(roleXO.id());
        }
        return convert(securitySystem.getAuthorizationManager(DEFAULT_SOURCE)
            .addRole(
                new Role(
                    roleXO.id(),
                    roleXO.name(),
                    roleXO.description(),
                    roleXO.source(),
                    false,
                    roleXO.roles(),
                    roleXO.privileges())));
      });
      return future.get();
    } catch (InterruptedException | ExecutionException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Error creating role", e);
    }
  }

  /**
   * Updates a role.
   *
   * @param roleXO to be updated
   * @return updated role
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:roles:update")
  @Validate(groups = {Update.class, Default.class})
  public RoleXO update(@NotNull @Valid final RoleXO roleXO) throws NoSuchAuthorizationManagerException {
    try {
      Future<RoleXO> future = virtualThreadExecutor.submit(() -> {
        Role roleToUpdate = new Role();
        roleToUpdate.setRoleId(roleXO.id());
        roleToUpdate.setName(roleXO.name());
        roleToUpdate.setDescription(roleXO.description());
        roleToUpdate.setSource(roleXO.source());
        roleToUpdate.setReadOnly(false);
        roleToUpdate.setRoles(roleXO.roles());
        roleToUpdate.setPrivileges(roleXO.privileges());
        roleToUpdate.setVersion(Integer.parseInt(roleXO.version()));
        return convert(securitySystem.getAuthorizationManager(DEFAULT_SOURCE)
            .updateRole(roleToUpdate));
      });
      return future.get();
    } catch (InterruptedException | ExecutionException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Error updating role", e);
    }
  }

  /**
   * Deletes a role.
   *
   * @param id of role to be deleted
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:roles:delete")
  @Validate
  public void remove(@NotEmpty final String id) throws NoSuchAuthorizationManagerException {
    try {
      Future<?> future = virtualThreadExecutor.submit(() -> {
        securitySystem.getAuthorizationManager(DEFAULT_SOURCE).deleteRole(id);
        return null;
      });
      future.get();
    } catch (InterruptedException | ExecutionException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Error removing role: " + id, e);
    }
  }

  /**
   * Convert role to XO.
   */
  private RoleXO convert(final Role input) {
    RoleXO roleXO = new RoleXO(
    		input.getRoleId(), 
    		String.valueOf(input.getVersion()), 
    		// Set source
    	    (DEFAULT_SOURCE.equals(input.getSource()) ||
    	        Strings2.isBlank(input.getSource())) ? "Nexus" : input.getSource(),
    	     // Set Name  
    	     Strings2.isBlank(input.getName()) ? input.getRoleId() : input.getName(),
    	    // Set description		 
    	     Strings2.isBlank(input.getDescription()) ? input.getRoleId() : input.getDescription(),
    	    // Set readonly 
    	    input.isReadOnly(),
    	    // Set privileges
    	    input.getPrivileges(), 
    	    // Set roles
    	    input.getRoles());
    return roleXO;
  }
}