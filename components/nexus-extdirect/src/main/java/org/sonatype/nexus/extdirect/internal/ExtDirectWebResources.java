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
package org.sonatype.nexus.extdirect.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.webresources.FileWebResource;
import org.sonatype.nexus.webresources.WebResource;
import org.sonatype.nexus.webresources.WebResourceBundle;

import com.google.common.collect.ImmutableList;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.webresources.WebResource.JAVASCRIPT;

/**
 * Ext.Direct web-resources.
 *
 * @since 3.0
 */
@Named
@Singleton
public class ExtDirectWebResources
    implements WebResourceBundle
{
  private static final Logger log = LoggerFactory.getLogger(ExtDirectWebResources.class);

  private final ApplicationDirectories directories;

  @Inject
  public ExtDirectWebResources(final ApplicationDirectories directories) {
    this.directories = checkNotNull(directories);
  }

  /**
   * Creates a WebResource from a file in the temporary directory.
   * Uses Java 21 Path API and String Templates for improved performance and readability.
   */
  private Optional<WebResource> create(final String fileName, final String path) {
    Path filePath = directories.getTemporaryDirectory().toPath().resolve(fileName);
    
    if (Files.exists(filePath)) {
      log.debug(STR."Creating web resource for file: \{fileName} at path: \{path}");
      return Optional.of(new FileWebResource(filePath.toFile(), path, JAVASCRIPT, true));
    } else {
      log.warn(STR."File not found: \{filePath}, web resource at \{path} will not be available");
      return Optional.empty();
    }
  }

  // FIXME: Would like to replace the generation here instead of relying on file which could be changed, etc
  // FIXME: Also we need a bit more control over the generation of this content so we can set the baseUrl etc

  /**
   * Returns the list of web resources provided by this bundle.
   * Leverages Java 21 features for improved resource handling and error reporting.
   */
  @Override
  public List<WebResource> getResources() {
    ImmutableList.Builder<WebResource> resources = ImmutableList.builder();
    
    create("nexus-extdirect/api.js", "/static/rapture/extdirect-prod.js")
        .ifPresent(resources::add);
    
    create("nexus-extdirect/api-debug.js", "/static/rapture/extdirect-debug.js")
        .ifPresent(resources::add);
    
    List<WebResource> result = resources.build();
    log.debug(STR."Providing \{result.size()} Ext.Direct web resources");
    
    return result;
  }
}