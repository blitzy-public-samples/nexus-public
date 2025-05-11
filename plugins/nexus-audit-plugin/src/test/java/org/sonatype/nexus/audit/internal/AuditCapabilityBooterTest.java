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
 * Tests for {@link AuditCapabilityBooter} with JUnit Jupiter and Mockito 4.11.0+.
 * 
 * <p>This test verifies that the AuditCapability is correctly registered during system boot.</p>
 *
 * <p>Updated for Java 21 compatibility as part of the migration from JUnit 4 to JUnit Jupiter.</p>
 */
@ExtendWith(MockitoExtension.class)
public class AuditCapabilityBooterTest
    extends TestSupport
{
  private AuditCapabilityBooter underTest;

  @Mock
  private CapabilityRegistry capabilityRegistry;

  @BeforeEach
  public void setup() {
    underTest = new AuditCapabilityBooter();
  }

  /**
   * Verifies that the AuditCapability is added to the registry during boot when it doesn't already exist.
   * 
   * <p>This test ensures that the capability is registered with the correct type and enabled state.</p>
   */
  @Test
  public void shouldAddAuditCapabilityWhenNotPresent() throws Exception {
    // Given the capability doesn't exist in the registry
    when(capabilityRegistry.get(any(CapabilityReferenceFilter.class))).thenReturn(null);
    
    // When the booter is executed
    underTest.boot(capabilityRegistry);

    // Then the capability should be added with the correct parameters
    verify(capabilityRegistry).addNonExposed(eq(AuditCapability.TYPE), eq(true), any(), any());
  }
}
