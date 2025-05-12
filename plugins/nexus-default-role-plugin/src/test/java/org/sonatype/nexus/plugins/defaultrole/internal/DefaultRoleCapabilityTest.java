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
package org.sonatype.nexus.plugins.defaultrole.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.plugins.defaultrole.DefaultRoleRealm;
import org.sonatype.nexus.security.realm.RealmManager;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DefaultRoleCapability} with Java 21 compatibility.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
class DefaultRoleCapabilityTest
    extends TestSupport
{
  private DefaultRoleCapability underTest;

  @Mock
  private RealmManager realmManager;

  @Mock
  private DefaultRoleRealm defaultRoleRealm;

  @Mock
  private DefaultRoleCapabilityConfiguration configuration;

  @BeforeEach
  void setup() {
    underTest = new DefaultRoleCapability(realmManager, defaultRoleRealm);
  }

  @Test
  void testOnPassivate_duringNormalOperation() {
    // Set the current thread name to simulate normal operation
    Thread.currentThread().setName("NormalOperationThread");

    // Execute the method under test
    underTest.onPassivate(configuration);

    // Verify expected interactions
    verify(defaultRoleRealm).setRole(null);
    verify(realmManager).disableRealm(DefaultRoleRealm.NAME);
  }

  @Test
  void testOnPassivate_duringShutdown() {
    // Set the current thread name to simulate Felix shutdown
    Thread.currentThread().setName("FelixStartLevel");

    // Execute the method under test
    underTest.onPassivate(configuration);

    // Verify no interactions with mocks during shutdown
    verifyNoInteractions(defaultRoleRealm);
    verifyNoInteractions(realmManager);
  }
}