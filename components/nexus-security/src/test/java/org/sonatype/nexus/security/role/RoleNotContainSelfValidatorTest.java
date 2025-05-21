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
package org.sonatype.nexus.security.role;

import java.util.Collection;
import java.util.Collections;

import javax.validation.ConstraintValidatorContext;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.Roles;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RoleNotContainSelfValidatorTest}.
 */
@ExtendWith(MockitoExtension.class)
public class RoleNotContainSelfValidatorTest
    extends TestSupport
{
  @Mock
  SecuritySystem securitySystem;

  @Mock
  AuthorizationManager authorizationManager;

  @Mock
  RoleNotContainSelf roleNotContainSelf;

  @Mock
  ConstraintValidatorContext context;

  RoleNotContainSelfValidator underTest;

  @BeforeEach
  public void setup() throws Exception {
    when(securitySystem.getAuthorizationManager(any())).thenReturn(authorizationManager);
    when(roleNotContainSelf.id()).thenReturn("getId");
    when(roleNotContainSelf.roleIds()).thenReturn("getRoleIds");
    when(context.buildConstraintViolationWithTemplate(any()))
        .thenReturn(mock(ConstraintValidatorContext.ConstraintViolationBuilder.class));
    underTest = new RoleNotContainSelfValidator(securitySystem);

    underTest.initialize(roleNotContainSelf);
  }

  @Test
  public void testIsValid_noId() {
    assertTrue(underTest.isValid(new TestObj(), context));
  }

  @Test
  public void testIsValid_valid() throws Exception {
    Role adminRole = createRole(Roles.ADMIN_ROLE_ID);
    Role parentRole = createRole("parentRole", "childRole");
    Role childRole = createRole("childRole", "grandchildRole");
    Role grandchildRole = createRole("grandchildRole", Roles.ADMIN_ROLE_ID);

    when(authorizationManager.getRole(Roles.ADMIN_ROLE_ID)).thenReturn(adminRole);
    when(authorizationManager.getRole("parentRole")).thenReturn(parentRole);
    when(authorizationManager.getRole("childRole")).thenReturn(childRole);
    when(authorizationManager.getRole("grandchildRole")).thenReturn(grandchildRole);

    assertTrue(underTest.isValid(new TestObj("parentRole", Collections.singleton("childRole")), context));
  }

  @Test
  public void testIsValid_invalid() throws Exception {
    Role adminRole = createRole(Roles.ADMIN_ROLE_ID);
    Role parentRole = createRole("parentRole", "childRole");
    Role childRole = createRole("childRole", "grandchildRole");
    Role grandchildRole = createRole("grandchildRole", "parentRole", Roles.ADMIN_ROLE_ID);

    when(authorizationManager.getRole(Roles.ADMIN_ROLE_ID)).thenReturn(adminRole);
    when(authorizationManager.getRole("parentRole")).thenReturn(parentRole);
    when(authorizationManager.getRole("childRole")).thenReturn(childRole);
    when(authorizationManager.getRole("grandchildRole")).thenReturn(grandchildRole);

    assertFalse(underTest.isValid(new TestObj("parentRole", Collections.singleton("childRole")), context));
  }

  private Role createRole(String id, String... childRoles) {
    Role role = new Role();
    role.setRoleId(id);
    for (String childRole : childRoles) {
      role.addRole(childRole);
    }

    return role;
  }

  private static class TestObj {
    private String id;
    private Collection<String> roleIds;

    public TestObj() {

    }

    public TestObj(String id, Collection<String> roleIds) {
      this.id = id;
      this.roleIds = roleIds;
    }

    public String getId() {
      return id;
    }

    public Collection<String> getRoleIds() {
      return roleIds;
    }
  }
}
