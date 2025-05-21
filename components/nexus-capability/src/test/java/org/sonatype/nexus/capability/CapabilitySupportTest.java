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
package org.sonatype.nexus.capability;

import java.net.URL;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.template.TemplateHelper;
import org.sonatype.nexus.common.template.TemplateParameters;
import org.sonatype.nexus.common.template.TemplateThrowableAdapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CapabilitySupportTest
    extends TestSupport
{
  @Mock
  private CapabilityContext context;

  @Mock
  private TemplateHelper templateHelper;

  private TestCapability underTest;

  @BeforeEach
  public void setup() {
    underTest = new TestCapability();
    underTest.init(context);
    underTest.setTemplateHelper(templateHelper);
  }

  @Test
  public void shouldReturnNullWhenRenderingMissingTemplate() {
    TemplateParameters params = new TemplateParameters()
        .set("cause", new TemplateThrowableAdapter(new Exception()));
    String templateName = "missing-template.vm";
    assertNull(underTest.render(templateName, params), STR."Template \{templateName} should return null when not found");
    verifyNoInteractions(templateHelper);
  }

  @Test
  public void shouldRenderFailureTemplateSuccessfully() {
    when(templateHelper.render(any(URL.class), any(TemplateParameters.class))).thenReturn("rendered");
    TemplateParameters params = new TemplateParameters()
        .set("cause", new TemplateThrowableAdapter(new Exception()));
    String templateName = "failure.vm";
    String expected = "rendered";
    
    String result = underTest.render(templateName, params);
    
    assertEquals(expected, result, STR."Template \{templateName} should render correctly");
    verify(templateHelper).render(underTest.getClass().getResource(templateName), params);
  }

  private class TestCapability
      extends CapabilitySupport<TestCapabilityConfig>
  {
    @Override
    protected TestCapabilityConfig createConfig(final Map<String, String> properties) throws Exception {
      // Using pattern matching to check if properties is empty or not
      if (properties instanceof Map<String, String> map && map.isEmpty()) {
        return new TestCapabilityConfig(); // Default config for empty properties
      }
      return new TestCapabilityConfig(); // Regular config creation
    }
  }

  private class TestCapabilityConfig
      extends CapabilityConfigurationSupport
  {
  }
}
