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
package org.sonatype.nexus.script.plugin.internal.security;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.SecurityConfiguration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;

/**
 * Tests for {@link ScriptSecurityContributor}.
 * 
 * <p>This test verifies that the security contributor correctly provides the expected
 * script-related privileges with the appropriate patterns.</p>
 * 
 * <p>Updated for Java 21 compatibility using JUnit Jupiter 5.10.1 lifecycle annotations.</p>
 * 
 * @since 3.0
 */
public class ScriptSecurityContributorTest
    extends TestSupport
{
  private ScriptSecurityContributor underTest;

  @BeforeEach
  public void setup() {
    underTest = new ScriptSecurityContributor();
  }

  /**
   * Verifies that the security contributor provides the expected script-related privileges
   * and no users, roles, or user-role mappings.
   */
  @Test
  public void testGetContribution() {
    SecurityConfiguration config = underTest.getContribution();
    assertThat(config.getUsers().size(), is(0));
    assertThat(config.getUserRoleMappings().size(), is(0));
    assertThat(config.getRoles().size(), is(0));
    assertThat(config.getPrivileges().stream().map(CPrivilege::getId).collect(toList()),
        containsInAnyOrder("nx-script-*-*", "nx-script-*-browse", "nx-script-*-read", "nx-script-*-edit",
            "nx-script-*-add", "nx-script-*-delete", "nx-script-*-run"));
  }
}