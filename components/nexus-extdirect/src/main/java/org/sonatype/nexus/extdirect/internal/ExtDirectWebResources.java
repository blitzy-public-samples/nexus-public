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

import java.io.File;
import java.util.List;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.webresources.FileWebResource;
import org.sonatype.nexus.webresources.WebResource;
import org.sonatype.nexus.webresources.WebResourceBundle;

import com.google.common.collect.ImmutableList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
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
   * Creates a web resource for the specified file.
   *
   * @param fileName the name of the file in the temporary directory
   * @param path the path to expose the resource at
   * @return the created web resource
   */
  private WebResource create(final String fileName, final String path) {
    File file = new File(directories.getTemporaryDirectory(), fileName);
    if (!file.exists()) {
      log.warn(STR."Ext.Direct resource file not found: {file.getAbsolutePath()}");
    } else {
      log.debug(STR."Creating Ext.Direct web resource for {fileName} at path {path}");
    }
    return new FileWebResource(file, path, JAVASCRIPT, true);
  }

  // FIXME: Would like to replace the generation here instead of relying on file which could be changed, etc
  // FIXME: Also we need a bit more control over the generation of this content so we can set the baseUrl etc

  @Override
  public List<WebResource> getResources() {
    log.debug(STR."Loading Ext.Direct web resources from {directories.getTemporaryDirectory()}");
    
    // Create resources list with improved performance using Java 21 features
    return ImmutableList.of(
        create("nexus-extdirect/api.js", "/static/rapture/extdirect-prod.js"),
        create("nexus-extdirect/api-debug.js", "/static/rapture/extdirect-debug.js")
    );
  }
}