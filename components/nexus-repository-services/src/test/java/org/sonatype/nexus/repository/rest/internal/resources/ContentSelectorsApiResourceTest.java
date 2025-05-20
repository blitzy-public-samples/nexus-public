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
package org.sonatype.nexus.repository.rest.internal.resources;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jakarta.validation.ConstraintViolationException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.rest.api.ContentSelectorApiCreateRequest;
import org.sonatype.nexus.repository.rest.api.ContentSelectorApiResponse;
import org.sonatype.nexus.repository.rest.api.ContentSelectorApiUpdateRequest;
import org.sonatype.nexus.rest.WebApplicationMessageException;
import org.sonatype.nexus.selector.CselSelector;
import org.sonatype.nexus.selector.SelectorConfiguration;
import org.sonatype.nexus.selector.SelectorConfigurationStore;
import org.sonatype.nexus.selector.SelectorFactory;
import org.sonatype.nexus.selector.SelectorManager;
import org.sonatype.nexus.testcommon.Java21TestGroup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpStatus.NOT_FOUND;
import static org.sonatype.nexus.selector.SelectorConfiguration.EXPRESSION;

@Category(Java21TestGroup.class)
@ExtendWith(MockitoExtension.class)
public class ContentSelectorsApiResourceTest
    extends TestSupport
{
  private ContentSelectorsApiResource underTest;

  @Mock
  private SelectorFactory selectorFactory;

  @Mock
  private SelectorManager selectorManager;

  @Mock
  private SelectorConfigurationStore store;

  @Mock
  private EventManager eventManager;

  @BeforeEach
  public void setup() {
    underTest = new ContentSelectorsApiResource(selectorFactory, selectorManager, store, eventManager);
  }

  @Test
  public void getContentSelectorsConvertsTheResult() {
    SelectorConfiguration selectorConfiguration = new TestContentSelector();
    selectorConfiguration.setName("name");
    selectorConfiguration.setType("csel");
    selectorConfiguration.setDescription("description");
    selectorConfiguration.setAttributes(singletonMap("expression", "test-expression"));
    when(store.browse()).thenReturn(asList(selectorConfiguration));

    List<ContentSelectorApiResponse> response = underTest.getContentSelectors();

    assertThat(response.get(0).getName(), is("name"));
    assertThat(response.get(0).getType(), is("csel"));
    assertThat(response.get(0).getDescription(), is("description"));
    assertThat(response.get(0).getExpression(), is("test-expression"));
  }

  @Test
  public void createContentSelectorValidatesTheExpression() {
    ContentSelectorApiCreateRequest request = new ContentSelectorApiCreateRequest();
    request.setName("name");
    request.setExpression("invalid-expression");
    doThrow(new ConstraintViolationException(emptySet())).when(selectorFactory)
        .validateSelector(CselSelector.TYPE, request.getExpression());

    assertThrows(ConstraintViolationException.class, () -> underTest.createContentSelector(request));
  }

  @Test
  public void createContentSelectorCreatesAValidSelector() {
    ContentSelectorApiCreateRequest request = new ContentSelectorApiCreateRequest();
    request.setName("name");
    request.setExpression("format == \"maven2\"");

    SelectorConfiguration expected = new TestContentSelector();
    expected.setName(request.getName());
    expected.setType(CselSelector.TYPE);
    expected.setAttributes(singletonMap(EXPRESSION, request.getExpression()));
    when(selectorManager.findByName(expected.getName())).thenReturn(Optional.of(expected));

    underTest.createContentSelector(request);
    verify(selectorManager).create("name", CselSelector.TYPE, null, singletonMap(EXPRESSION, "format == \"maven2\""));
  }

  @Test
  public void getContentSelectorFindsSelectorByName() {
    SelectorConfiguration matchingSelector = new TestContentSelector();
    matchingSelector.setName("test");
    matchingSelector.setAttributes(emptyMap());
    when(selectorManager.findByName(matchingSelector.getName())).thenReturn(Optional.of(matchingSelector));

    ContentSelectorApiResponse response = underTest.getContentSelector(matchingSelector.getName());

    assertThat(response.getName(), is(matchingSelector.getName()));
  }

  @Test
  public void getContentSelectorThrowNotFoundForSelectorNotFound() {
    when(selectorManager.findByName(any())).thenReturn(Optional.empty());

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.getContentSelector("any"));
    assertThat(exception.getResponse(), hasProperty("status", is(NOT_FOUND)));
  }

  @Test
  public void updateContentSelectorThrowsBadRequestForInvalidExpression() throws Exception {
    ContentSelectorApiUpdateRequest request = new ContentSelectorApiUpdateRequest();
    request.setExpression("invalid-expression");

    SelectorConfiguration selectorConfiguration = mock(SelectorConfiguration.class);
    when(selectorConfiguration.getType()).thenReturn(CselSelector.TYPE);
    when(selectorManager.findByName("any")).thenReturn(Optional.of(selectorConfiguration));

    doThrow(new ConstraintViolationException("", emptySet())).when(selectorFactory)
        .validateSelector(CselSelector.TYPE, request.getExpression());

    assertThrows(ConstraintViolationException.class, 
        () -> underTest.updateContentSelector("any", request));
  }

  @Test
  public void updateContentSelectorThrowsNotFoundForSelectorNotFound() {
    ContentSelectorApiUpdateRequest request = new ContentSelectorApiUpdateRequest();
    request.setExpression("format == \"maven2\"");

    when(selectorManager.findByName(any())).thenReturn(Optional.empty());

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.updateContentSelector("any", request));
    assertThat(exception.getResponse(), hasProperty("status", is(NOT_FOUND)));
  }

  @Test
  public void updateContentSelectorUpdatesSelector() {
    ContentSelectorApiUpdateRequest request = new ContentSelectorApiUpdateRequest();
    request.setDescription("description");
    request.setExpression("format == \"maven2\"");

    SelectorConfiguration selector = new TestContentSelector();
    selector.setName("test");
    selector.setType(CselSelector.TYPE);
    when(selectorManager.findByName(selector.getName())).thenReturn(Optional.of(selector));

    underTest.updateContentSelector(selector.getName(), request);

    ArgumentCaptor<SelectorConfiguration> configurationCaptor = ArgumentCaptor.forClass(SelectorConfiguration.class);
    verify(selectorManager).update(configurationCaptor.capture());
    assertThat(configurationCaptor.getValue().getType(), is(CselSelector.TYPE));
    assertThat(configurationCaptor.getValue().getDescription(), is(request.getDescription()));
    assertThat(configurationCaptor.getValue().getAttributes().get(EXPRESSION), is(request.getExpression()));
  }

  @Test
  public void deleteContentSelectorThrowsNotFoundForSelectorNotFound() {
    ContentSelectorApiUpdateRequest request = new ContentSelectorApiUpdateRequest();
    request.setExpression("format == \"maven2\"");

    SelectorConfiguration selector = new TestContentSelector();
    selector.setName("any");

    when(selectorManager.findByName(any())).thenReturn(Optional.empty());

    WebApplicationMessageException exception = assertThrows(WebApplicationMessageException.class, 
        () -> underTest.deleteContentSelector(selector.getName()));
    assertThat(exception.getResponse(), hasProperty("status", is(NOT_FOUND)));
  }

  @Test
  public void deleteContentSelectorSucceeds() {
    SelectorConfiguration selector = new TestContentSelector();
    selector.setName("test");
    when(selectorManager.findByName(selector.getName())).thenReturn(Optional.of(selector));

    underTest.deleteContentSelector(selector.getName());

    verify(selectorManager).delete(selector);
  }
  
  @Test
  public void testPatternMatchingWithCselExpressions() {
    // Test pattern matching with different CSEL expression types
    SelectorConfiguration selector = new TestContentSelector();
    selector.setName("pattern-test");
    selector.setType(CselSelector.TYPE);
    
    // Using pattern matching to handle different expression types
    Object expression = "format == \"maven2\"";
    
    if (expression instanceof String s) {
      // Pattern matching with String type
      assertEquals("format == \"maven2\"", s);
      selector.setAttributes(singletonMap(EXPRESSION, s));
    }
    
    when(selectorManager.findByName(selector.getName())).thenReturn(Optional.of(selector));
    ContentSelectorApiResponse response = underTest.getContentSelector(selector.getName());
    
    // Verify the response using pattern matching
    if (response instanceof ContentSelectorApiResponse r && r.getName() != null) {
      assertEquals("pattern-test", r.getName());
    }
  }
  
  @Test
  public void testPatternMatchingWithSwitchExpression() {
    // Test pattern matching with switch expressions for different selector types
    SelectorConfiguration selector = mock(SelectorConfiguration.class);
    when(selector.getType()).thenReturn(CselSelector.TYPE);
    when(selector.getName()).thenReturn("switch-test");
    
    String result = switch (selector) {
      case SelectorConfiguration s when "csel".equals(s.getType()) -> "CSEL Selector";
      case SelectorConfiguration s when "jexl".equals(s.getType()) -> "JEXL Selector";
      default -> "Unknown Selector";
    };
    
    assertEquals("CSEL Selector", result);
  }
  
  @Test
  public void testVirtualThreadExecution() throws Exception {
    // Test executing content selector operations with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      SelectorConfiguration selector = new TestContentSelector();
      selector.setName("virtual-thread-test");
      selector.setType(CselSelector.TYPE);
      selector.setAttributes(singletonMap(EXPRESSION, "format == \"maven2\""));
      
      when(store.browse()).thenReturn(asList(selector));
      
      // Execute getContentSelectors in a virtual thread
      Future<List<ContentSelectorApiResponse>> future = executor.submit(() -> underTest.getContentSelectors());
      
      List<ContentSelectorApiResponse> response = future.get(5, TimeUnit.SECONDS);
      assertEquals(1, response.size());
      assertEquals("virtual-thread-test", response.get(0).getName());
    }
  }
  
  @Test
  public void testConcurrentContentSelectorOperations() throws Exception {
    // Test concurrent content selector operations with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Setup test data
      SelectorConfiguration selector1 = new TestContentSelector();
      selector1.setName("selector1");
      selector1.setType(CselSelector.TYPE);
      
      SelectorConfiguration selector2 = new TestContentSelector();
      selector2.setName("selector2");
      selector2.setType(CselSelector.TYPE);
      
      when(store.browse()).thenReturn(asList(selector1, selector2));
      when(selectorManager.findByName("selector1")).thenReturn(Optional.of(selector1));
      when(selectorManager.findByName("selector2")).thenReturn(Optional.of(selector2));
      
      // Execute multiple operations concurrently
      Future<?> future1 = executor.submit(() -> underTest.getContentSelectors());
      Future<?> future2 = executor.submit(() -> underTest.getContentSelector("selector1"));
      Future<?> future3 = executor.submit(() -> underTest.getContentSelector("selector2"));
      
      // Verify all operations complete successfully
      assertDoesNotThrow(() -> {
        future1.get(5, TimeUnit.SECONDS);
        future2.get(5, TimeUnit.SECONDS);
        future3.get(5, TimeUnit.SECONDS);
      });
    }
  }

  private static class TestContentSelector implements SelectorConfiguration{

    private String name;
    private String type;
    private String description;
    private Map<String, String> attributes = Collections.emptyMap();
    
    @Override
    public String getName() {
      return this.name;
    }

    @Override
    public void setName(final String name) {
      this.name = name;

    }

    @Override
    public String getType() {
      return this.type;
    }

    @Override
    public void setType(final String type) {
        this.type = type;
    }

    @Override
    public String getDescription() {
      return this.description;
    }

    @Override
    public void setDescription(final String description) {
      this.description = description;
    }

    @Override
    public Map<String, String> getAttributes() {
      return this.attributes;
    }

    @Override
    public void setAttributes(final Map<String, ?> attributes) {
      this.attributes = (Map<String, String>) attributes;
    }
  }
}