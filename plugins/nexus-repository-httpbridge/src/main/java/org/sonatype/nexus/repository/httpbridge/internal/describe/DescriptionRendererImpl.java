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
package org.sonatype.nexus.repository.httpbridge.internal.describe;

import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.template.EscapeHelper;
import org.sonatype.nexus.common.template.TemplateHelper;
import org.sonatype.nexus.common.template.TemplateParameters;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Default {@link DescriptionRenderer}.
 *
 * @since 3.0
 */
@Named
@Singleton
public class DescriptionRendererImpl
    implements DescriptionRenderer
{
  private static final String TEMPLATE_RESOURCE = "describeHtml.vm";
  
  // Threshold size for using Virtual Threads (in number of items)
  private static final int VIRTUAL_THREAD_THRESHOLD = 100;
  
  // Timeout for Virtual Thread execution (in seconds)
  private static final int VIRTUAL_THREAD_TIMEOUT = 30;

  private final TemplateHelper templateHelper;

  private final ObjectMapper objectMapper;

  private final URL template;

  @Inject
  public DescriptionRendererImpl(final TemplateHelper templateHelper) {
    this.templateHelper = checkNotNull(templateHelper);
    
    // Use JsonMapper.builder() instead of direct ObjectMapper instantiation for Java 21 compatibility
    objectMapper = JsonMapper.builder()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();
        
    template = getClass().getResource(TEMPLATE_RESOURCE);
    checkNotNull(template);
  }

  @Override
  public String renderHtml(final Description description) {
    TemplateParameters params = templateHelper.parameters();
    params.setAll(description.getParameters());
    params.set("items", description.getItems());
    params.set("esc", new EscapeHelper());
    return templateHelper.render(template, params);
  }

  @Override
  public String renderJson(final Description description) {
    // For large descriptions, use Virtual Threads to avoid blocking platform threads
    if (description.getItems().size() > VIRTUAL_THREAD_THRESHOLD) {
      return renderJsonWithVirtualThread(description);
    } else {
      return renderJsonDirect(description);
    }
  }
  
  /**
   * Renders JSON directly on the current thread.
   *
   * @param description the description to render
   * @return the JSON string representation
   */
  private String renderJsonDirect(final Description description) {
    try {
      return objectMapper.writeValueAsString(description);
    } catch (Exception e) {
      // Use pattern matching for more elegant exception handling
      return switch (e) {
        case JsonProcessingException jpe -> 
          throw new RuntimeException("Error processing JSON: " + jpe.getMessage(), jpe);
        case IllegalArgumentException iae -> 
          throw new RuntimeException("Invalid argument for JSON serialization: " + iae.getMessage(), iae);
        default -> 
          throw new RuntimeException("Unexpected error during JSON serialization: " + e.getMessage(), e);
      };
    }
  }
  
  /**
   * Renders JSON using a Virtual Thread for large descriptions to avoid blocking platform threads.
   *
   * @param description the description to render
   * @return the JSON string representation
   */
  private String renderJsonWithVirtualThread(final Description description) {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> future = executor.submit(() -> renderJsonDirect(description));
      return future.get(VIRTUAL_THREAD_TIMEOUT, TimeUnit.SECONDS);
    } catch (Exception e) {
      // Use pattern matching for more elegant exception handling
      return switch (e) {
        case java.util.concurrent.TimeoutException te -> 
          throw new RuntimeException("JSON rendering timed out after " + VIRTUAL_THREAD_TIMEOUT + " seconds", te);
        case java.util.concurrent.ExecutionException ee -> 
          throw new RuntimeException("Error during JSON rendering: " + ee.getCause().getMessage(), ee.getCause());
        case java.lang.InterruptedException ie -> {
          Thread.currentThread().interrupt(); // Restore interrupted status
          yield "JSON rendering was interrupted";
        }
        default -> 
          throw new RuntimeException("Unexpected error during JSON rendering: " + e.getMessage(), e);
      };
    }
  }
}