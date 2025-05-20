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
package org.sonatype.nexus.repository.security;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import org.apache.shiro.authz.permission.DomainPermission;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for security permission functionality.
 */
@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class SecurityTrialTest
    extends TestSupport
{
  @Test
  public void shouldAcceptWildcardPermissionString() {
    assertDoesNotThrow(() -> new WildcardPermission("foo:bar:*:baz"));
  }

  @Test
  public void shouldAcceptDomainPermissionString() {
    assertDoesNotThrow(() -> new DomainPermission("foo,bar", "read,write"));
  }

  private static class CustomPermission
      extends DomainPermission
  {
    CustomPermission(final String actions, final String targets) {
      super(actions, targets);
    }
  }

  @Test
  public void shouldAcceptCustomDomainPermissionString() {
    assertDoesNotThrow(() -> new CustomPermission("foo,bar", "read,write"));
  }

  @Test
  public void shouldImplyPermissionWhenWildcardMatches() {
    WildcardPermission granted = new WildcardPermission("test:*");
    WildcardPermission permission = new WildcardPermission("test:foo");
    assertTrue(granted.implies(permission), "Wildcard permission should imply more specific permission");
  }
}