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
package org.sonatype.nexus.repository.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.SecurityConfiguration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RepositoryViewSecurityContributor}.
 */
@ExtendWith(MockitoExtension.class)
public class RepositoryViewSecurityContributorTest
    extends TestSupport
{
  /**
   * Custom annotation for Java 21 specific tests.
   */
  @Target({ElementType.TYPE, ElementType.METHOD})
  @Retention(RetentionPolicy.RUNTIME)
  @Tag("java21")
  @Test
  public @interface Java21Test {
  }

  private RepositoryViewSecurityContributor underTest;

  @BeforeEach
  public void setup() {
    underTest = new RepositoryViewSecurityContributor();
  }

  @Test
  void getContributionShouldReturnCorrectPrivileges() {
    SecurityConfiguration config = underTest.getContribution();
    assertEquals(0, config.getUsers().size());
    assertEquals(0, config.getUserRoleMappings().size());
    assertEquals(0, config.getRoles().size());
    assertThat(config.getPrivileges().stream().map(CPrivilege::getId).collect(toList()),
        containsInAnyOrder("nx-repository-view-*-*-*", "nx-repository-view-*-*-browse", "nx-repository-view-*-*-read",
            "nx-repository-view-*-*-edit", "nx-repository-view-*-*-add", "nx-repository-view-*-*-delete"));
  }

  @Java21Test
  void privilegeDescriptionsShouldUseStringTemplates() {
    SecurityConfiguration config = underTest.getContribution();
    
    // Verify that privilege descriptions follow the expected format
    config.getPrivileges().forEach(privilege -> {
      String action = privilege.getProperties().get("actions");
      String description = privilege.getDescription();
      
      if ("*".equals(action)) {
        assertTrue(description.startsWith("All"), 
            "All-action privilege should have description starting with 'All'");
      } else {
        assertTrue(description.startsWith(action.substring(0, 1).toUpperCase() + action.substring(1)),
            "Action-specific privilege should have capitalized action name at the start of description");
      }
      
      assertTrue(description.endsWith(" permissions for all repository views"),
          "Privilege description should end with standard suffix");
    });
  }
}