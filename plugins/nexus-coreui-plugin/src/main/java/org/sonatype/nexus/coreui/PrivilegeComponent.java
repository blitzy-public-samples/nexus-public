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

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import com.google.common.collect.Maps;
import com.softwarementors.extjs.djn.config.annotations.DirectAction;
import com.softwarementors.extjs.djn.config.annotations.DirectMethod;
import org.apache.shiro.authz.annotation.RequiresAuthentication;
import org.apache.shiro.authz.annotation.RequiresPermissions;

import org.sonatype.nexus.extdirect.DirectComponent;
import org.sonatype.nexus.extdirect.DirectComponentSupport;
import org.sonatype.nexus.extdirect.model.PagedResponse;
import org.sonatype.nexus.extdirect.model.StoreLoadParameters;
import org.sonatype.nexus.formfields.FormField;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.authz.NoSuchAuthorizationManagerException;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.privilege.PrivilegeDescriptor;
import org.sonatype.nexus.security.privilege.ReadonlyPrivilegeException;
import org.sonatype.nexus.validation.Validate;
import org.sonatype.nexus.validation.group.Create;
import org.sonatype.nexus.validation.group.Update;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.groups.Default;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.security.user.UserManager.DEFAULT_SOURCE;

/**
 * Privilege {@link DirectComponent}
 */
@Named
@Singleton
@DirectAction(action = "coreui_Privilege")
public class PrivilegeComponent
    extends DirectComponentSupport
{
  private final SecuritySystem securitySystem;

  private final List<PrivilegeDescriptor> privilegeDescriptors;
  
  // Executor for virtual threads
  private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public PrivilegeComponent(
      final SecuritySystem securitySystem,
      final List<PrivilegeDescriptor> privilegeDescriptors)
  {
    this.securitySystem = checkNotNull(securitySystem);
    this.privilegeDescriptors = checkNotNull(privilegeDescriptors);
  }

  /**
   * Retrieves privileges.
   *
   * @return a list of privileges
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:privileges:read")
  public PagedResponse<PrivilegeXO> read(final StoreLoadParameters parameters) {
    // Using CompletableFuture with virtual threads for I/O-bound operation
    CompletableFuture<List<PrivilegeXO>> privilegesFuture = CompletableFuture.supplyAsync(() -> 
        securitySystem.listPrivileges()
            .stream()
            .map(this::convert)
            .collect(Collectors.toList()), // NOSONAR
        virtualThreadExecutor);
    
    List<PrivilegeXO> privileges = privilegesFuture.join();
    return extractPage(parameters, privileges);
  }

  /**
   * Retrieves privileges and extracts name and id fields only.
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:privileges:read")
  public List<ReferenceXO> readReferences() {
    // Using CompletableFuture with virtual threads for I/O-bound operation
    CompletableFuture<List<ReferenceXO>> referencesFuture = CompletableFuture.supplyAsync(() ->
        securitySystem.listPrivileges()
            .stream()
            .map(privilege -> new ReferenceXO(privilege.getId(), privilege.getName()))
            .collect(Collectors.toList()), // NOSONAR
        virtualThreadExecutor);
    
    return referencesFuture.join();
  }

  /**
   * Return only those records matching the given parameters. Will apply a filter and/or sort to client-exposed
   * properties of {@link PrivilegeXO}: name, description, permission, type.
   */
  @RequiresPermissions("nexus:privileges:read")
  public PagedResponse<PrivilegeXO> extractPage(final StoreLoadParameters parameters, final List<PrivilegeXO> xos) {
    log.trace("requesting page with parameters: {} and size of: {}", parameters, xos.size());

    checkArgument(parameters.getStart() == null || parameters.getStart() == 0 || parameters.getStart() < xos.size(),
        "Requested to skip more results than available");

    List<PrivilegeXO> result = new ArrayList<>(xos);
    if (parameters.getFilter() != null && !parameters.getFilter().isEmpty()) {
      String filter = parameters.getFilter().get(0).getValue();
      // Using pattern matching for switch to improve readability
      result = xos.stream()
          .filter(xo -> {
            // Check each field for the filter value
            return switch(xo) {
              case PrivilegeXO p when p.getName().contains(filter) -> true;
              case PrivilegeXO p when p.getDescription().contains(filter) -> true;
              case PrivilegeXO p when p.getPermission().contains(filter) -> true;
              case PrivilegeXO p when p.getType().contains(filter) -> true;
              default -> false;
            };
          })
          .collect(Collectors.toList());
    }

    if (parameters.getSort() != null && !parameters.getSort().isEmpty()) {
      // assume one sort, not multiple props
      boolean ascending = "ASC".equals(parameters.getSort().get(0).getDirection());
      String sortProperty = parameters.getSort().get(0).getProperty();
      result.sort((a, b) -> {
        int comparison = getFieldValue(a, sortProperty).compareTo(getFieldValue(b, sortProperty));
        return ascending ? comparison : -comparison;
      });
    }

    int size = result.size();
    int start = parameters.getStart() != null ? parameters.getStart() : 0;
    int limit = parameters.getLimit() != null ? parameters.getLimit() : size;
    int end = Math.min(start + limit, size);
    List<PrivilegeXO> page = result.subList(start, end);
    return new PagedResponse<>(size, page);
  }

  /**
   * Retrieve available privilege types.
   *
   * @return a list of privilege types
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresPermissions("nexus:privileges:read")
  public List<PrivilegeTypeXO> readTypes() {
    return privilegeDescriptors.stream()
        .map(descriptor -> {
          PrivilegeTypeXO xo = new PrivilegeTypeXO();
          xo.setId(descriptor.getType());
          xo.setName(descriptor.getName());
          xo.setFormFields(convertFormFields(descriptor));
          return xo;
        })
        .collect(Collectors.toList()); // NOSONAR
  }

  /**
   * Creates a privilege.
   *
   * @param privilege the privilege info
   * @return the created privilege XO
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:privileges:create")
  @Validate(groups = {Create.class, Default.class})
  public PrivilegeXO create(@NotNull @Valid final PrivilegeXO privilege) throws NoSuchAuthorizationManagerException {
    // Using CompletableFuture with virtual threads for I/O-bound operation
    CompletableFuture<PrivilegeXO> privilegeFuture = CompletableFuture.supplyAsync(() -> {
      try {
        AuthorizationManager authorizationManager = securitySystem.getAuthorizationManager(DEFAULT_SOURCE);
        privilege.withId(
            privilege.getName()); // Use name as privilege ID (note: eventually IDs should go away in favor of names)
        return convert(authorizationManager.addPrivilege(convert(privilege)));
      } catch (NoSuchAuthorizationManagerException e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    try {
      return privilegeFuture.join();
    } catch (RuntimeException e) {
      if (e.getCause() instanceof NoSuchAuthorizationManagerException) {
        throw (NoSuchAuthorizationManagerException) e.getCause();
      }
      throw e;
    }
  }

  /**
   * Updates a privilege.
   *
   * @param privilege the privilege info
   * @return the updated privilege XO
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:privileges:update")
  @Validate(groups = {Update.class, Default.class})
  public PrivilegeXO update(
      @NotNull @Valid final PrivilegeXO privilege) throws NoSuchAuthorizationManagerException, IllegalAccessException
  {
    // Using CompletableFuture with virtual threads for I/O-bound operation
    CompletableFuture<PrivilegeXO> privilegeFuture = CompletableFuture.supplyAsync(() -> {
      try {
        AuthorizationManager authorizationManager = securitySystem.getAuthorizationManager(DEFAULT_SOURCE);
        return convert(authorizationManager.updatePrivilege(convert(privilege)));
      } catch (NoSuchAuthorizationManagerException | ReadonlyPrivilegeException e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    try {
      return privilegeFuture.join();
    } catch (RuntimeException e) {
      if (e.getCause() instanceof NoSuchAuthorizationManagerException) {
        throw (NoSuchAuthorizationManagerException) e.getCause();
      } else if (e.getCause() instanceof ReadonlyPrivilegeException) {
        throw new IllegalAccessException("Privilege [" + privilege.getId() + "] is readonly and cannot be updated");
      }
      throw e;
    }
  }

  /**
   * Deletes a privilege, if is not readonly.
   *
   * @param id of privilege to be deleted
   */
  @DirectMethod
  @Timed
  @ExceptionMetered
  @RequiresAuthentication
  @RequiresPermissions("nexus:privileges:delete")
  @Validate
  public void remove(@NotEmpty final String id) throws NoSuchAuthorizationManagerException, IllegalAccessException {
    // Using CompletableFuture with virtual threads for I/O-bound operation
    CompletableFuture<Void> removeFuture = CompletableFuture.runAsync(() -> {
      try {
        AuthorizationManager authorizationManager = securitySystem.getAuthorizationManager(DEFAULT_SOURCE);
        authorizationManager.deletePrivilege(id);
      } catch (NoSuchAuthorizationManagerException | ReadonlyPrivilegeException e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    try {
      removeFuture.join();
    } catch (RuntimeException e) {
      if (e.getCause() instanceof NoSuchAuthorizationManagerException) {
        throw (NoSuchAuthorizationManagerException) e.getCause();
      } else if (e.getCause() instanceof ReadonlyPrivilegeException) {
        throw new IllegalAccessException("Privilege [" + id + "] is readonly and cannot be deleted");
      }
      throw e;
    }
  }

  /**
   * Convert privilege to XO.
   */
  PrivilegeXO convert(Privilege input) {
    return switch (input) {
      case null -> throw new IllegalArgumentException("Input privilege cannot be null");
      case Privilege p -> new PrivilegeXO()
          .withId(p.getId())
          .withVersion(String.valueOf(p.getVersion()))
          .withName(p.getName() != null ? p.getName() : p.getId())
          .withDescription(p.getDescription() != null ? p.getDescription() : p.getId())
          .withType(p.getType())
          .withReadOnly(p.isReadOnly())
          .withProperties(Maps.newHashMap(p.getProperties()))
          .withPermission(p.getPermission().toString());
    };
  }

  /**
   * Convert XO to privilege.
   */
  Privilege convert(PrivilegeXO input) {
    return switch (input) {
      case null -> throw new IllegalArgumentException("Input privilege XO cannot be null");
      case PrivilegeXO p -> {
        Privilege privilege = new Privilege();
        privilege.setId(p.getId());
        privilege.setVersion(p.getVersion().isEmpty() ? 0 : Integer.parseInt(p.getVersion()));
        privilege.setName(p.getName());
        privilege.setDescription(p.getDescription());
        privilege.setType(p.getType());
        privilege.setProperties(Maps.newHashMap(p.getProperties()));
        yield privilege;
      }
    };
  }

  private Comparable getFieldValue(Object obj, String fieldName) {
    try {
      Field field = obj.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      return (Comparable<?>) field.get(obj);
    }
    catch (NoSuchFieldException | IllegalAccessException e) {
      return null;
    }
  }

  private List<FormFieldXO> convertFormFields(final PrivilegeDescriptor descriptor) {
    if (descriptor.getFormFields() == null) {
      return null;
    }

    return descriptor.getFormFields()
        .stream()
        .map(f -> FormFieldXO.create((FormField) f))
        .collect(Collectors.toList()); // NOSONAR
  }
}