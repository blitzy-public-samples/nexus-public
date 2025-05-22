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
import java.util.concurrent.ExecutionException;

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
  
  // Threshold for description size to use virtual threads (number of items)
  private static final int LARGE_DESCRIPTION_THRESHOLD = 100;

  private final TemplateHelper templateHelper;

  private final ObjectMapper objectMapper;

  private final URL template;

  @Inject
  public DescriptionRendererImpl(final TemplateHelper templateHelper) {
    this.templateHelper = checkNotNull(templateHelper);
    // Use JsonMapper.builder() for Java 21 compatibility
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
    // For large descriptions, use virtual threads to avoid blocking platform threads
    if (description.getItems().size() > LARGE_DESCRIPTION_THRESHOLD) {
      return renderJsonWithVirtualThread(description);
    } else {
      return renderJsonDirectly(description);
    }
  }
  
  /**
   * Renders JSON directly on the current thread for smaller descriptions.
   *
   * @param description the description to render
   * @return the JSON string representation
   */
  private String renderJsonDirectly(final Description description) {
    try {
      return objectMapper.writeValueAsString(description);
    } catch (Exception e) {
      // Using Java 21 pattern matching for exceptions
      switch (e) {
        case JsonProcessingException jpe -> {
          throw new RuntimeException("Error processing JSON: " + jpe.getMessage(), jpe);
        }
        case IllegalArgumentException iae -> {
          throw new RuntimeException("Invalid argument for JSON serialization: " + iae.getMessage(), iae);
        }
        default -> {
          throw new RuntimeException("Unexpected error during JSON serialization", e);
        }
      }
    }
  }
  
  /**
   * Renders JSON using a virtual thread for larger descriptions to avoid blocking platform threads.
   * This is particularly useful for I/O-bound operations with large data structures.
   *
   * @param description the description to render
   * @return the JSON string representation
   */
  private String renderJsonWithVirtualThread(final Description description) {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> future = executor.submit(() -> objectMapper.writeValueAsString(description));
      return future.get();
    } catch (Exception e) {
      // Using Java 21 pattern matching for exceptions
      switch (e) {
        case ExecutionException ee when ee.getCause() instanceof JsonProcessingException -> {
          throw new RuntimeException("Error processing JSON in virtual thread: " + ee.getCause().getMessage(), ee.getCause());
        }
        case InterruptedException ie -> {
          Thread.currentThread().interrupt(); // Preserve interrupt status
          throw new RuntimeException("JSON rendering interrupted", ie);
        }
        default -> {
          throw new RuntimeException("Unexpected error during JSON serialization in virtual thread", e);
        }
      }
    }
  }
}