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
package java21;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.apt.internal.debian.DebianVersion;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * Tests demonstrating the use of Java 21 String Templates for APT repository messages and logging.
 * 
 * @since 3.60
 */
public class AptStringTemplatesTest
    extends TestSupport
{
  private static final String PACKAGE_NAME = "nexus-apt-package";
  private static final String PACKAGE_VERSION = "1.2.3-1ubuntu1";
  private static final String REPOSITORY_NAME = "apt-proxy";
  private static final String ARCHITECTURE = "amd64";
  
  /**
   * Test basic string template usage with APT package information.
   */
  @Test
  public void testBasicStringTemplate() {
    // Using Java 21 String Templates for simple message formatting
    String message = STR."Package \{PACKAGE_NAME} version \{PACKAGE_VERSION} is available";
    
    assertThat(message, is(equalTo("Package nexus-apt-package version 1.2.3-1ubuntu1 is available")));
  }
  
  /**
   * Test error message formatting for APT repositories using String Templates.
   */
  @Test
  public void testErrorMessageFormatting() {
    String errorCode = "APT-404";
    int statusCode = 404;
    
    // Using String Templates for error message formatting
    String errorMessage = STR."\{errorCode}: Package \{PACKAGE_NAME} not found in repository \{REPOSITORY_NAME} with status \{statusCode}";
    
    assertThat(errorMessage, is(equalTo("APT-404: Package nexus-apt-package not found in repository apt-proxy with status 404")));
  }
  
  /**
   * Test log message formatting with multiple embedded expressions.
   */
  @Test
  public void testLogMessageFormatting() {
    boolean isSnapshot = true;
    int componentCount = 42;
    
    // Using String Templates for log message with multiple expressions
    String logMessage = STR."Repository \{REPOSITORY_NAME} contains \{componentCount} components. " + 
                        STR."Architecture: \{ARCHITECTURE}, IsSnapshot: \{isSnapshot}";
    
    assertThat(logMessage, is(equalTo("Repository apt-proxy contains 42 components. Architecture: amd64, IsSnapshot: true")));
  }
  
  /**
   * Test String Templates with DebianVersion objects.
   */
  @Test
  public void testStringTemplatesWithDebianVersion() {
    DebianVersion version = new DebianVersion(PACKAGE_VERSION);
    
    // Using String Templates with method calls on objects
    String versionInfo = STR."Package \{PACKAGE_NAME} has epoch \{version.getEpoch()}, " + 
                         STR."upstream version \{version.getUpstreamVersion()} and " + 
                         STR."debian revision \{version.getDebianRevision()}";
    
    assertThat(versionInfo, is(equalTo("Package nexus-apt-package has epoch 0, upstream version 1.2.3 and debian revision 1ubuntu1")));
  }
  
  /**
   * Test String Templates with expressions containing calculations.
   */
  @Test
  public void testStringTemplatesWithCalculations() {
    int availablePackages = 120;
    int totalPackages = 150;
    
    // Using String Templates with calculations in embedded expressions
    String statusMessage = STR."Repository \{REPOSITORY_NAME} health: \{availablePackages * 100 / totalPackages}% packages available " + 
                           STR."(\{availablePackages} of \{totalPackages})";
    
    assertThat(statusMessage, is(equalTo("Repository apt-proxy health: 80% packages available (120 of 150)")));
  }
  
  /**
   * Test String Templates with conditional expressions.
   */
  @Test
  public void testStringTemplatesWithConditionalExpressions() {
    boolean isSecure = true;
    String protocol = "https";
    
    // Using String Templates with conditional expressions
    String securityMessage = STR."Repository \{REPOSITORY_NAME} is \{isSecure ? "secure" : "insecure"} " + 
                             STR."using \{protocol.toUpperCase()} protocol";
    
    assertThat(securityMessage, is(equalTo("Repository apt-proxy is secure using HTTPS protocol")));
  }
}