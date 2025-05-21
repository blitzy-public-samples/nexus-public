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
package org.sonatype.nexus.internal.template;

import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.common.template.EscapeHelper;
import org.sonatype.nexus.common.template.TemplateHelper;
import org.sonatype.nexus.common.template.TemplateParameters;

import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Default {@link TemplateHelper}.
 *
 * @since 3.0
 */
@Named
@Singleton
public class TemplateHelperImpl
    extends ComponentSupport
    implements TemplateHelper
{
  private final ApplicationVersion applicationVersion;

  private final VelocityEngine velocityEngine;
  
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public TemplateHelperImpl(
      final ApplicationVersion applicationVersion,
      final VelocityEngine velocityEngine)
  {
    this.applicationVersion = checkNotNull(applicationVersion);
    this.velocityEngine = checkNotNull(velocityEngine);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public TemplateParameters parameters() {
    TemplateParameters params = new TemplateParameters();
    params.set("nexusVersion", applicationVersion.getVersion());
    params.set("nexusEdition", applicationVersion.getEdition());
    params.set("nexusBrandedEditionAndVersion", applicationVersion.getBrandedEditionAndVersion());
    params.set("relativePath", BaseUrlHolder.getRelativePath());
    params.set("urlSuffix", applicationVersion.getVersion()); // for cache busting
    params.set("esc", new EscapeHelper());
    return params;
  }

  /**
   * Shutdown the virtual thread executor when the component is destroyed.
   */
  @PreDestroy
  public void shutdown() {
    log.debug(STR."Shutting down virtual thread executor for template rendering");
    virtualThreadExecutor.shutdown();
  }

  @Override
  public String render(final URL template, final TemplateParameters parameters) {
    checkNotNull(template);
    checkNotNull(parameters);

    log.trace(STR."Rendering template: \{template} w/params: \{parameters}");

    Future<String> renderTask = null;
    try {
      // Submit the rendering task to a virtual thread for better concurrency
      renderTask = virtualThreadExecutor.submit(() -> renderInternal(template, parameters));
      return renderTask.get();
    }
    catch (Exception e) {
      // Cancel the task if it's still running
      if (renderTask != null && !renderTask.isDone()) {
        renderTask.cancel(true);
      }
      
      // Use pattern matching to handle different exception types
      if (e instanceof RuntimeException re) {
        throw re;
      }
      else if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new RuntimeException(STR."Failed to render template: \{template}", e);
    }
  }
  
  /**
   * Internal method to render a template using Velocity.
   * This method is designed to be executed in a virtual thread.
   *
   * @param template the URL of the template to render
   * @param parameters the parameters to use for rendering
   * @return the rendered template as a string
   */
  private String renderInternal(final URL template, final TemplateParameters parameters) {
    // Use the current thread's context class loader to ensure compatibility with Java 21 modules
    ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
    try {
      // Set the class loader to ensure template resources can be found in the module environment
      Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
      
      // Use try-with-resources to ensure proper resource cleanup
      try (Reader input = new InputStreamReader(template.openStream(), StandardCharsets.UTF_8)) {
        StringWriter buff = new StringWriter();
        velocityEngine.evaluate(new VelocityContext(parameters.get()), buff, template.getFile(), input);

        String result = buff.toString();
        log.trace(STR."Result: \{result}");

        return result;
      }
    }
    catch (Exception e) {
      // Use pattern matching for exception handling
      if (e instanceof RuntimeException re) {
        throw re;
      }
      throw new RuntimeException(STR."Error rendering template: \{template.getFile()}", e);
    }
    finally {
      // Restore the original class loader
      Thread.currentThread().setContextClassLoader(originalClassLoader);
    }
  }
}