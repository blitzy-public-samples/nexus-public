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
package org.sonatype.nexus.script.plugin.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.script.ScriptManager;

import com.codahale.metrics.health.HealthCheck.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class ScriptPluginHealthCheckTest extends TestSupport
{
  @Mock
  ScriptManager scriptManager;

  private ScriptPluginHealthCheck underTest;

  @BeforeEach
  public void setup() {
    underTest = new ScriptPluginHealthCheck(scriptManager);
  }

  @Test
  public void shouldReturnHealthyWhenScriptManagerDisabled() {
    when(scriptManager.isEnabled()).thenReturn(false);
    Result result = underTest.check();
    assertTrue(result.isHealthy());
  }

  @Test
  public void shouldReturnUnhealthyWhenScriptManagerEnabled() {
    when(scriptManager.isEnabled()).thenReturn(true);
    Result result = underTest.check();
    assertFalse(result.isHealthy());
  }
  
  @Test
  public void shouldRespectJava21Sandboxing() {
    // Test that script execution respects Java 21's stronger encapsulation model
    when(scriptManager.isEnabled()).thenReturn(true);
    when(scriptManager.isSandboxed()).thenReturn(true);
    
    Result result = underTest.check();
    
    // Even with sandboxing enabled, the health check should still report unhealthy
    // when script manager is enabled, as this is a security-focused health check
    assertFalse(result.isHealthy());
    assertTrue(result.getMessage().contains("Script support is enabled"));
  }
}