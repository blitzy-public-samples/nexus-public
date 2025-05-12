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
package org.sonatype.nexus.repository.apt.internal;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.apt.AptFormat;
import org.sonatype.nexus.repository.security.RepositoryFormatSecurityContributor;

/**
 * APT format security contributor that sets up repository security privileges for APT repositories.
 * Compatible with Java 21 and OSGi component scanning in Karaf 4.4.4.
 * 
 * @since 3.17
 * @see RepositoryFormatSecurityContributor
 */
@Named
@Singleton
public class AptFormatSecurityContributor
    extends RepositoryFormatSecurityContributor
{
  /**
   * Creates a new APT format security contributor.
   * 
   * @param format the APT format instance
   */
  @Inject
  public AptFormatSecurityContributor(@Named(AptFormat.NAME) final Format format) {
    super(format);
  }
}