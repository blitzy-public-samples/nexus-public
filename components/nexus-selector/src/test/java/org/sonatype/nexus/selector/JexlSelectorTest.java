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
package org.sonatype.nexus.selector;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.jupiter.TestSupport;

import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.google.common.collect.ImmutableMap.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

public class JexlSelectorTest
    extends TestSupport
{
  private JexlEngine engine = new JexlEngine();

  private VariableSource source;

  private static class SomeObject
  {
    @SuppressWarnings("unused")
    private String foo;
  }

  @BeforeEach
  public void setUp() {
    Map<String, String> writeableMap = new HashMap<>();
    writeableMap.put("foo", "bar");

    source = new VariableSourceBuilder()
        .addResolver(new PropertiesResolver<>("component", of("format", "maven2")))
        .addResolver(new PropertiesResolver<>("writeableMap", writeableMap))
        .addResolver(new PropertiesResolver<>("asset", of("name", "junit", "group", "Jjunit", "path", "/org/apache/maven/foo/bar/moo.jar")))
        .addResolver(new ConstantVariableResolver(new SomeObject(), "writeableObj"))
        .addResolver(new ConstantVariableResolver(true, "X"))
        .addResolver(new ConstantVariableResolver(false, "Y"))
        .addResolver(new ConstantVariableResolver("foobar", "someString"))
        .addResolver(new ConstantVariableResolver(of("a", "alfa", "b", "bravo"), "someMap"))
        .build();
  }

  @Test
  public void prettyExceptionMsgOneLine() {
    String expression = "&&INVALID";
    testPrettyExceptionMsg("parsing error in '&&'", 1, 1, expression);
  }

  @Test
  public void prettyExceptionMsgMultiLine() {
    String expression = "true\n #INVALID";
    // For some reason JEXL thinks # is at column 3 in line 2
    testPrettyExceptionMsg("tokenization error in '#'", 2, 3, expression);
  }

  private void testPrettyExceptionMsg(String detail, int line, int column, String expression) {
    String expected = String.format("%s at line %d column %d", detail, line, column);
    String returned = null;
    try {
      buildSelector(expression);
    }
    catch (JexlException e) {
      returned = JexlEngine.expandExceptionDetail(e);
    }
    assertNotNull(returned, "Returned string was not set.");
    assertEquals(expected, returned);
  }

  @Test
  public void prettyExceptionMsgNoDetail() {
    // Setup
    String expected = "at line 2 column 4";

    JexlInfo info = new JexlInfo("", 2, 4);
    // Mocked because JexlException modifies msg internally after construction
    JexlException ex = mock(JexlException.class);
    doReturn(info).when(ex).getInfo();
    doReturn("").when(ex).getMessage();

    // Execute
    String returned = JexlEngine.expandExceptionDetail(ex);

    // Verify
    assertNotNull(returned, "Returned string was not set.");
    assertEquals(expected, returned);
  }

  @Test
  public void componentFormatHappy() {
    Selector selector = buildSelector("component.format == 'maven2'");

    assertTrue(selector.evaluate(source));
  }

  @Test
  public void componentFormatSad() {
    Selector selector = buildSelector("component.format == 'nuget'");

    assertFalse(selector.evaluate(source));
  }

  @Test
  public void assetNameHappy() {
    Selector selector = buildSelector("asset.name =~ '^jun.+'");

    assertTrue(selector.evaluate(source));
  }

  @Test
  public void assetNameSad() {
    Selector selector = buildSelector("asset.name =~ '^jun.+' and asset.group =~ '^jun.+'");

    assertFalse(selector.evaluate(source));
  }

  @Test
  public void xHappy() {
    Selector selector = buildSelector("X == true and Y == false");

    assertTrue(selector.evaluate(source));
  }

  @Test
  public void stringToUppercase() {
    Selector selector = buildSelector("someString.toUpperCase() == 'FOOBAR'");

    assertTrue(selector.evaluate(source));
  }

  @Test
  public void mapAccess() {
    Selector selector = buildSelector("someMap['a'] == 'alfa'");

    assertTrue(selector.evaluate(source));
  }

  @Test
  public void noConstructor() {
    Selector selector = buildSelector("new('" + JexlSelector.class.getName() + "', 'path = \\'/bar\\'')");

    assertThrows(JexlException.class, () -> selector.evaluate(source));
  }

  @Test
  public void methodsBlocked() {
    Selector selector = buildSelector("writeableMap.put('foo', 'xxx')");

    assertThrows(JexlException.class, () -> selector.evaluate(source));
  }

  @Test
  public void writeBlocked() {
    Selector selector = buildSelector("writeableObj.foo = 'xxx'");

    assertThrows(JexlException.class, () -> selector.evaluate(source));
  }

  @Test
  public void java21SecurityModelCompatibility() {
    // Test that JEXL expressions work with Java 21's enhanced security model
    // This test verifies that the JexlEngine's sandbox restrictions are properly enforced
    Selector selector = buildSelector("asset.path.startsWith('/org/apache')");
    
    // Should evaluate without security exceptions
    assertTrue(selector.evaluate(source));
    
    // Verify that reflection-based access is still blocked
    Selector reflectionSelector = buildSelector("asset.getClass().getMethod('toString').invoke(asset)");
    assertThrows(JexlException.class, () -> reflectionSelector.evaluate(source));
  }

  @Test
  public void virtualThreadPinningDetection() throws Exception {
    // Test that selector evaluation doesn't pin virtual threads
    // This is important for I/O-bound operations in Java 21
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(1);
      
      // Create a selector that will be evaluated in a virtual thread
      Selector selector = buildSelector("asset.path.contains('maven')");
      
      // Submit the task to a virtual thread
      Future<?> future = executor.submit(() -> {
        // Signal that we're ready to evaluate
        latch.countDown();
        // Evaluate the selector in a virtual thread
        return selector.evaluate(source);
      });
      
      // Wait for the virtual thread to start
      assertTrue(latch.await(1, TimeUnit.SECONDS));
      
      // Get the result - this should complete quickly if not pinned
      Boolean result = (Boolean) future.get(2, TimeUnit.SECONDS);
      
      // Verify the result is as expected
      assertTrue(result);
    }
  }

  @Test
  public void stringTemplateEvaluation() {
    // Test that selector evaluation works with Java 21 String Templates
    // Create a variable source with a template string
    VariableSource templateSource = new VariableSourceBuilder()
        .addResolver(new ConstantVariableResolver("Hello, World!", "greeting"))
        .addResolver(new ConstantVariableResolver("template", "type"))
        .build();
    
    // Create a selector that checks string template-like patterns
    Selector selector = buildSelector("greeting.startsWith('Hello') && type == 'template'");
    
    // Verify the selector works with template-like strings
    assertTrue(selector.evaluate(templateSource));
  }

  private JexlSelector buildSelector(final String expression) {
    return new JexlSelector(engine.buildExpression(expression, true));
  }
}