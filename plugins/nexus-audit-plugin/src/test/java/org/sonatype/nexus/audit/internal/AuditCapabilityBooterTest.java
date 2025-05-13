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
package org.sonatype.nexus.audit.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.capability.CapabilityReferenceFilterBuilder.CapabilityReferenceFilter;
import org.sonatype.nexus.capability.CapabilityRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AuditCapabilityBooter} using JUnit Jupiter (JUnit 5) with Mockito 4.11.0+.
 * <p>
 * This test class has been migrated from JUnit 4 to JUnit Jupiter as part of the Java 21 upgrade.
 */
@ExtendWith(MockitoExtension.class)
class AuditCapabilityBooterTest
    extends TestSupport
{
  private AuditCapabilityBooter underTest;

  @Mock
  private CapabilityRegistry capabilityRegistry;

  @BeforeEach
  void setup() {
    underTest = new AuditCapabilityBooter();
  }

  @Test
  void bootIsEnabled() throws Exception {
    when(capabilityRegistry.get(any(CapabilityReferenceFilter.class))).thenReturn(null);
    underTest.boot(capabilityRegistry);

    verify(capabilityRegistry).addNonExposed(eq(AuditCapability.TYPE), eq(true), any(), any());
  }
}