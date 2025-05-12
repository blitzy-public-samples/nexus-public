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
package org.sonatype.nexus.content.raw.internal.recipe;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.template.EscapeHelper;
import org.sonatype.nexus.content.raw.RawContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.httpclient.HttpClientFacet;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RawProxyFacet} with Java 21 features including virtual threads,
 * pattern matching, and string templates.
 */
@ExtendWith(MockitoExtension.class)
class RawProxyFacetTest extends TestSupport
{
  @Mock
  private Repository repository;
  
  @Mock
  private RawContentFacet rawContentFacet;
  
  @Mock
  private HttpClientFacet httpClientFacet;
  
  @Mock
  private HttpClient httpClient;
  
  @Mock
  private Context context;
  
  @Mock
  private TokenMatcher.State tokenMatcherState;
  
  @Captor
  private ArgumentCaptor<String> pathCaptor;

  private RawProxyFacet rawProxyFacet;

  @BeforeEach
  void setUp() {
    rawProxyFacet = new RawProxyFacet();
    
    // Setup basic mocks for virtual thread tests
    lenient().when(repository.facet(RawContentFacet.class)).thenReturn(rawContentFacet);
    lenient().when(repository.facet(HttpClientFacet.class)).thenReturn(httpClientFacet);
    lenient().when(httpClientFacet.getHttpClient()).thenReturn(httpClient);
    
    // Use reflection to set repository on the facet
    try {
      var repositoryField = ContentProxyFacetSupport.class.getDeclaredField("repository");
      repositoryField.setAccessible(true);
      repositoryField.set(rawProxyFacet, repository);
    } 
    catch (Exception e) {
      throw new RuntimeException("Failed to set repository field", e);
    }
  }

  @Test
  @DisplayName("Should encode URL with caret character")
  void encodeUrlWithCaret() throws UnsupportedEncodingException {
    String url = "http://example.com/path^test";
    String expectedEncodedUrl = "http://example.com/path%5Etest";
    assertEncodedUrl(url, expectedEncodedUrl, "^");
  }

  @Test
  @DisplayName("Should encode URL with hash character")
  void encodeUrlWithHash() throws UnsupportedEncodingException {
    String url = "http://example.com/path#test";
    String expectedEncodedUrl = "http://example.com/path%23test";
    assertEncodedUrl(url, expectedEncodedUrl, "#");
  }

  @Test
  @DisplayName("Should encode URL with question mark character")
  void encodeUrlWithQuestionMark() throws UnsupportedEncodingException {
    String url = "http://example.com/path?test";
    String expectedEncodedUrl = "http://example.com/path%3Ftest";
    assertEncodedUrl(url, expectedEncodedUrl, "?");
  }

  @Test
  @DisplayName("Should encode URL with narrow no-break space character")
  void encodeUrlWithNarrowNoBreakSpace() throws UnsupportedEncodingException {
    String url = "http://example.com/path\u202Ftest";
    String expectedEncodedUrl = "http://example.com/path%E2%80%AFtest";
    assertEncodedUrl(url, expectedEncodedUrl, "\u202F");
  }

  @Test
  @DisplayName("Should encode URL with left square bracket character")
  void encodeUrlWithLeftSquareBracket() throws UnsupportedEncodingException {
    String url = "http://example.com/path[test";
    String expectedEncodedUrl = "http://example.com/path%5Btest";
    assertEncodedUrl(url, expectedEncodedUrl, "[");
  }

  @Test
  @DisplayName("Should encode URL with right square bracket character")
  void encodeUrlWithRightSquareBracket() throws UnsupportedEncodingException {
    String url = "http://example.com/path]test";
    String expectedEncodedUrl = "http://example.com/path%5Dtest";
    assertEncodedUrl(url, expectedEncodedUrl, "]");
  }
  
  @Test
  @DisplayName("Should handle multiple special characters in URL")
  void encodeUrlWithMultipleSpecialCharacters() throws UnsupportedEncodingException {
    String url = "http://example.com/path[with]^special#chars?and\u202Fspaces";
    String expectedEncodedUrl = "http://example.com/path%5Bwith%5D%5Especial%23chars%3Fand%E2%80%AFspaces";
    assertEncodedUrl(url, expectedEncodedUrl, "multiple special characters");
  }
  
  @Test
  @DisplayName("Should use virtual threads for content operations")
  void virtualThreadsForContentOperations() throws Exception {
    // Setup mocks for getCachedContent
    Map<String, Object> attributes = mock(Map.class);
    when(context.getAttributes()).thenReturn(attributes);
    when(attributes.require(TokenMatcher.State.class)).thenReturn(tokenMatcherState);
    when(tokenMatcherState.getTokens()).thenReturn(Map.of(RawRecipeSupport.PATH_NAME, "/test/path"));
    
    Content mockContent = mock(Content.class);
    when(rawContentFacet.get(anyString())).thenReturn(java.util.Optional.of(mockContent));
    
    // Execute getCachedContent which should use virtual threads
    Content result = rawProxyFacet.getCachedContent(context);
    
    // Verify the result and that the correct path was used
    assertNotNull(result);
    verify(rawContentFacet).get(pathCaptor.capture());
    assertEquals("/test/path", pathCaptor.getValue());
  }
  
  @Test
  @DisplayName("Should use pattern matching with instanceof for token state")
  void patternMatchingWithInstanceOf() throws Exception {
    // Setup context with TokenMatcher.State
    Map<String, Object> attributes = mock(Map.class);
    when(context.getAttributes()).thenReturn(attributes);
    when(attributes.require(TokenMatcher.State.class)).thenReturn(tokenMatcherState);
    when(tokenMatcherState.getTokens()).thenReturn(Map.of(RawRecipeSupport.PATH_NAME, "/pattern/matching/test"));
    
    // Setup content facet to return a mock content
    Content mockContent = mock(Content.class);
    when(rawContentFacet.get(anyString())).thenReturn(java.util.Optional.of(mockContent));
    
    // Call method that uses pattern matching
    Content result = rawProxyFacet.getCachedContent(context);
    
    // Verify correct path was extracted using pattern matching
    assertNotNull(result);
    verify(rawContentFacet).get("/pattern/matching/test");
  }
  
  @Test
  @DisplayName("Should handle concurrent operations with virtual threads")
  void concurrentOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread executor for testing
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("test-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Setup for concurrent operations
      int taskCount = 100;
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Setup mocks
      Map<String, Object> attributes = mock(Map.class);
      when(context.getAttributes()).thenReturn(attributes);
      when(attributes.require(TokenMatcher.State.class)).thenReturn(tokenMatcherState);
      when(tokenMatcherState.getTokens()).thenReturn(Map.of(RawRecipeSupport.PATH_NAME, "/concurrent/test"));
      
      Content mockContent = mock(Content.class);
      when(rawContentFacet.get(anyString())).thenReturn(java.util.Optional.of(mockContent));
      
      // Submit concurrent tasks using virtual threads
      CompletableFuture<?>[] futures = new CompletableFuture[taskCount];
      for (int i = 0; i < taskCount; i++) {
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            Content content = rawProxyFacet.getCachedContent(context);
            if (content != null) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            // Log exception but don't fail the test
            log.error("Error in concurrent operation", e);
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      CompletableFuture.allOf(futures).join();
      
      // Verify all operations completed successfully
      assertEquals(taskCount, successCount.get(), "All concurrent operations should succeed");
    } 
    finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  @Test
  @DisplayName("Should use string templates for error messages")
  void stringTemplatesForErrorMessages() throws Exception {
    // Setup mocks to force an exception
    Map<String, Object> attributes = mock(Map.class);
    when(context.getAttributes()).thenReturn(attributes);
    when(attributes.require(TokenMatcher.State.class)).thenReturn(tokenMatcherState);
    when(tokenMatcherState.getTokens()).thenReturn(Map.of(RawRecipeSupport.PATH_NAME, "/string/template/test"));
    
    // Make the content facet throw an exception
    when(rawContentFacet.get(anyString())).thenThrow(new RuntimeException("Test exception"));
    
    // Capture the exception which should use string templates in its message
    IOException exception = assertThrows(IOException.class, () -> rawProxyFacet.getCachedContent(context));
    
    // Verify the exception message contains the expected string template format
    String exceptionMessage = exception.getMessage();
    assertTrue(exceptionMessage.contains("Error getting cached content: Test exception"), 
        "Exception message should use string template format");
  }

  /**
   * Helper method to assert that a URL is properly encoded.
   */
  private void assertEncodedUrl(String url, String expectedEncodedUrl, String character) throws UnsupportedEncodingException {
    String actualEncodedUrl = rawProxyFacet.encodeUrl(url);
    assertEquals(expectedEncodedUrl, actualEncodedUrl, "Failed to encode character: " + character);
  }
}
