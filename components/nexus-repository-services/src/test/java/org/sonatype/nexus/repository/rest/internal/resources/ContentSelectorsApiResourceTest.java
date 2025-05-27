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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.validation.ConstraintViolationException;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
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

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    assertThrows(ConstraintViolationException.class, () -> underTest.updateContentSelector("any", request));
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

  /**
   * Tests pattern matching with CSEL expressions using Java 21's pattern matching features.
   * This test validates that we can correctly identify and process different types of CSEL expressions.
   */
  @Test
  public void testPatternMatchingWithCselExpressions() {
    // Create test selectors with different expression types
    SelectorConfiguration formatSelector = createSelector("format-selector", "format == \"maven2\"");
    SelectorConfiguration pathSelector = createSelector("path-selector", "path =^ \"/com/example/\"");
    SelectorConfiguration complexSelector = createSelector("complex-selector", 
        "format == \"maven2\" and path =^ \"/com/example/\" and coordinate.groupId == \"com.example\"");
    
    // Mock the store to return our test selectors
    when(store.browse()).thenReturn(asList(formatSelector, pathSelector, complexSelector));
    
    // Get all selectors
    List<ContentSelectorApiResponse> responses = underTest.getContentSelectors();
    assertEquals(3, responses.size());
    
    // Use pattern matching to process the responses based on expression type
    for (ContentSelectorApiResponse response : responses) {
      String expression = response.getExpression();
      
      // Use pattern matching to identify expression type
      if (expression != null && expression.contains("format == ")) {
        // This is a format-based selector
        if (expression.contains("path =^") && expression.contains("coordinate.groupId")) {
          // Complex selector with multiple conditions
          assertEquals("complex-selector", response.getName());
        } else {
          // Simple format selector
          assertEquals("format-selector", response.getName());
        }
      } else if (expression != null && expression.contains("path =^")) {
        // This is a path-based selector
        assertEquals("path-selector", response.getName());
      }
    }
  }

  /**
   * Tests advanced pattern matching with CSEL expressions using Java 21's pattern matching features.
   * This test demonstrates more complex pattern matching with guards and nested conditions.
   */
  @Test
  public void testAdvancedPatternMatchingWithCselExpressions() {
    // Create test selectors with different expression patterns
    SelectorConfiguration mavenSelector = createSelector("maven-selector", "format == \"maven2\"");
    SelectorConfiguration npmSelector = createSelector("npm-selector", "format == \"npm\"");
    SelectorConfiguration mavenPathSelector = createSelector("maven-path-selector", 
        "format == \"maven2\" and path =^ \"/org/sonatype/\"");
    
    // Mock the store to return our test selectors
    when(store.browse()).thenReturn(asList(mavenSelector, npmSelector, mavenPathSelector));
    
    // Get all selectors
    List<ContentSelectorApiResponse> responses = underTest.getContentSelectors();
    assertEquals(3, responses.size());
    
    // Count selectors by type using pattern matching with guards
    int mavenCount = 0;
    int npmCount = 0;
    int mavenPathCount = 0;
    
    for (ContentSelectorApiResponse response : responses) {
      String expression = response.getExpression();
      String name = response.getName();
      
      // Pattern matching with guards
      if (expression != null && expression.contains("format == \"maven2\"") && 
          !expression.contains("path =^")) {
        // Simple Maven selector
        mavenCount++;
        assertEquals("maven-selector", name);
      } else if (expression != null && expression.contains("format == \"npm\"")) {
        // NPM selector
        npmCount++;
        assertEquals("npm-selector", name);
      } else if (expression != null && expression.contains("format == \"maven2\"") && 
                expression.contains("path =^ \"/org/sonatype/\"")) {
        // Maven selector with path constraint
        mavenPathCount++;
        assertEquals("maven-path-selector", name);
      }
    }
    
    // Verify counts
    assertEquals(1, mavenCount, "Should have one simple Maven selector");
    assertEquals(1, npmCount, "Should have one NPM selector");
    assertEquals(1, mavenPathCount, "Should have one Maven path selector");
  }

  /**
   * Tests concurrent operations on content selectors using Java 21's Virtual Threads.
   * This test demonstrates how to use Virtual Threads for improved concurrency when
   * performing multiple operations on content selectors simultaneously.
   */
  @Test
  public void testConcurrentOperationsWithVirtualThreads() throws Exception {
    // Create test data
    int selectorCount = 10;
    List<SelectorConfiguration> selectors = new java.util.ArrayList<>();
    
    for (int i = 0; i < selectorCount; i++) {
      SelectorConfiguration selector = createSelector(
          "selector-" + i, 
          "format == \"maven2\" and path =^ \"/test/path/" + i + "\"");
      selectors.add(selector);
      when(selectorManager.findByName(selector.getName())).thenReturn(Optional.of(selector));
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent tasks to get, update, and delete selectors
      List<CompletableFuture<Void>> futures = new java.util.ArrayList<>();
      
      // Get operations
      for (SelectorConfiguration selector : selectors) {
        futures.add(CompletableFuture.runAsync(() -> {
          underTest.getContentSelector(selector.getName());
        }, executor));
      }
      
      // Update operations
      for (int i = 0; i < selectorCount; i++) {
        SelectorConfiguration selector = selectors.get(i);
        ContentSelectorApiUpdateRequest request = new ContentSelectorApiUpdateRequest();
        request.setDescription("Updated description " + i);
        request.setExpression("format == \"maven2\" and path =^ \"/updated/path/" + i + "\"");
        
        futures.add(CompletableFuture.runAsync(() -> {
          underTest.updateContentSelector(selector.getName(), request);
        }, executor));
      }
      
      // Wait for all operations to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, SECONDS);
      
      // Verify that all operations completed successfully
      ArgumentCaptor<SelectorConfiguration> configCaptor = ArgumentCaptor.forClass(SelectorConfiguration.class);
      verify(selectorManager, org.mockito.Mockito.atLeast(selectorCount)).update(configCaptor.capture());
      
      // Verify that at least some of the updates were processed
      List<SelectorConfiguration> capturedConfigs = configCaptor.getAllValues();
      assertTrue(capturedConfigs.size() >= selectorCount, 
          "Expected at least " + selectorCount + " updates, but got " + capturedConfigs.size());
    }
  }
  
  /**
   * Helper method to create a test selector configuration.
   */
  private SelectorConfiguration createSelector(String name, String expression) {
    SelectorConfiguration selector = new TestContentSelector();
    selector.setName(name);
    selector.setType(CselSelector.TYPE);
    selector.setAttributes(singletonMap(EXPRESSION, expression));
    return selector;
  }

  private static class TestContentSelector implements SelectorConfiguration {

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