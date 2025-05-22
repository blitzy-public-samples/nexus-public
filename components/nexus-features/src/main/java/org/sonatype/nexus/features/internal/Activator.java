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
package org.sonatype.nexus.features.internal;

import org.apache.karaf.features.FeaturesService;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.StringTemplate.STR;
import static java.lang.System.getProperty;

/**
 * Installs the fast {@link FeaturesService} wrapper.
 * Compatible with Apache Karaf 4.4.4 and Java 21.
 *
 * @since 3.19
 */
public class Activator
    implements BundleActivator
{
  private static final Logger log = LoggerFactory.getLogger(Activator.class);
  
  private static final String DISABLE_PROPERTY = "karaf.disableFastFeatures";
  
  private FeaturesWrapper wrapper;

  @Override
  public void start(final BundleContext context) throws Exception {
    // Check if fast features are disabled via system property
    String disableProperty = getProperty(DISABLE_PROPERTY);
    boolean fastFeaturesDisabled = "true".equalsIgnoreCase(disableProperty);
    
    if (fastFeaturesDisabled) {
      log.info(STR."Fast features disabled by \{DISABLE_PROPERTY}=\{disableProperty}");
      return;
    }
    
    log.info(STR."Activating fast FeaturesService wrapper for Karaf 4.4.4 compatibility");
    try {
      wrapper = new FeaturesWrapper(context);
      wrapper.open();
      log.debug(STR."Fast FeaturesService wrapper activated successfully");
    } 
    catch (Exception e) {
      log.error(STR."Failed to activate fast FeaturesService wrapper: \{e.getMessage()}", e);
      throw e;
    }
  }

  @Override
  public void stop(final BundleContext context) throws Exception {
    if (wrapper != null) {
      log.info(STR."Deactivating fast FeaturesService wrapper");
      try {
        wrapper.close();
        log.debug(STR."Fast FeaturesService wrapper deactivated successfully");
      } 
      catch (Exception e) {
        log.error(STR."Error deactivating fast FeaturesService wrapper: \{e.getMessage()}", e);
        throw e;
      }
      finally {
        wrapper = null;
      }
    }
  }
}
