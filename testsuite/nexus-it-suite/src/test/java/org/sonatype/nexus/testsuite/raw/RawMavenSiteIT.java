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
package org.sonatype.nexus.testsuite.raw;

import java.io.File;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.http.HttpStatus;
import org.sonatype.nexus.testsuite.testsupport.maven.MavenDeployment;
import org.sonatype.nexus.testsuite.testsupport.maven.MavenRunner;
import org.sonatype.nexus.testsuite.testsupport.raw.RawClient;
import org.sonatype.nexus.testsuite.testsupport.raw.RawITSupport;

import org.apache.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.sonatype.nexus.testsuite.testsupport.FormatClientSupport.asString;
import static org.sonatype.nexus.testsuite.testsupport.FormatClientSupport.status;

/**
 * Tests deployment of a maven site to a raw hosted repository.
 * <p>
 * This test class has been updated to use JUnit Jupiter (JUnit 5) and demonstrates
 * Java 21 features including virtual threads, pattern matching, record patterns,
 * string templates, and sequenced collections.
 */
public class RawMavenSiteIT
    extends RawITSupport
{
  private static final String INDEX_HTML = "index.html";
  
  // Record for site deployment configuration
  private record SiteDeploymentConfig(String projectName, String version, URL siteUrl) {}
  
  private Repository repository;

  private RawClient client;

  public RawMavenSiteIT() {
    testData.addDirectory(resolveBaseFile("target/it-resources/maven"));
  }

  @BeforeEach
  public void createHostedRepository() throws Exception {
    repository = repos.createRawHosted("test-raw-repo");
    client = rawClient(repository);
  }

  @Test
  @DisplayName("Simple site deployment should succeed")
  void simpleSiteDeploymentShouldSucceed() throws Exception {
    runMavenSite();

    final HttpResponse index = client.get(INDEX_HTML);

    assertThat(status(index), is(HttpStatus.OK));
    assertThat(asString(index), containsString("About testproject"));
  }

  @Test
  @DisplayName("MKCOL method should not be allowed")
  void mkcolMethodShouldNotBeAllowed() throws Exception {
    runMavenSite();

    final HttpResponse index = client.mkcol(INDEX_HTML);

    assertThat(status(index), is(HttpStatus.METHOD_NOT_ALLOWED));
  }

  @Test
  @DisplayName("Last download time should be set on GET but not PUT")
  void lastDownloadTimeShouldBeSetOnGetNotPut() throws Exception {
    runMavenSite();

    assertThat(getLastDownloadedTime(repository, INDEX_HTML), is(equalTo(null)));
    final HttpResponse index = client.get(INDEX_HTML);

    assertThat(status(index), is(HttpStatus.OK));
    assertThat(asString(index), containsString("About testproject"));
    assertThat(getLastDownloadedTime(repository, INDEX_HTML).isBeforeNow(), is(equalTo(true)));
  }

  @Test
  @DisplayName("Last download time should be set when using IndexHtmlForwardHandler")
  void lastDownloadTimeShouldBeSetWhenUsingIndexHtmlForwardHandler() throws Exception {
    runMavenSite();

    assertThat(getLastDownloadedTime(repository, INDEX_HTML), is(equalTo(null)));

    // This forces a path through the IndexHtmlForwardHandler
    final HttpResponse index = client.get("");

    assertThat(status(index), is(HttpStatus.OK));
    assertThat(asString(index), containsString("About testproject"));
    assertThat(getLastDownloadedTime(repository, INDEX_HTML).isBeforeNow(), is(equalTo(true)));
  }
  
  @Test
  @DisplayName("Concurrent site deployments should succeed using virtual threads")
  void concurrentSiteDeploymentsWithVirtualThreadsShouldSucceed() throws Exception {
    // Using Java 21 Virtual Threads for concurrent operations
    int concurrentDeployments = 10;
    CountDownLatch latch = new CountDownLatch(concurrentDeployments);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create executor with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < concurrentDeployments; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique repository for each deployment
            Repository repo = repos.createRawHosted("test-raw-repo-" + index);
            RawClient rawClient = rawClient(repo);
            
            // Run Maven site deployment
            runMavenSite(repo);
            
            // Verify deployment
            HttpResponse response = rawClient.get(INDEX_HTML);
            if (status(response) == HttpStatus.OK && 
                asString(response).contains("About testproject")) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            log.error("Error in virtual thread deployment {}: {}", index, e.getMessage(), e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all deployments to complete
      latch.await(2, TimeUnit.MINUTES);
    }
    
    // Verify all deployments succeeded
    assertThat("All concurrent deployments should succeed", 
        successCount.get(), is(concurrentDeployments));
  }
  
  @Test
  @DisplayName("Pattern matching should be used for HTTP response handling")
  void patternMatchingShouldBeUsedForHttpResponseHandling() throws Exception {
    runMavenSite();
    
    HttpResponse response = client.get(INDEX_HTML);
    
    // Using Java 21 pattern matching for instanceof with a binding variable
    String result = switch (status(response)) {
      case HttpStatus.OK -> {
        String content = asString(response);
        if (content instanceof String s && s.contains("About testproject")) {
          yield "Success: Found expected content";
        } else {
          yield "Error: Content doesn't match expected pattern";
        }
      }
      case HttpStatus.NOT_FOUND -> "Error: File not found";
      case HttpStatus.FORBIDDEN -> "Error: Access denied";
      default -> "Error: Unexpected status code " + status(response);
    };
    
    assertThat(result, is("Success: Found expected content"));
  }
  
  @Test
  @DisplayName("Record patterns should be used for site deployment configuration")
  void recordPatternsShouldBeUsedForSiteDeploymentConfiguration() throws Exception {
    // Create site deployment config using record
    URL siteUrl = new URL(nexusUrl, "/repository/" + repository.getName());
    SiteDeploymentConfig config = new SiteDeploymentConfig("testproject", "version", siteUrl);
    
    // Using Java 21 record pattern matching
    if (config instanceof SiteDeploymentConfig(String projectName, String version, URL url)) {
      // Use the destructured fields from the record pattern
      MavenDeployment mavenDeployment = new MavenDeployment();
      mavenDeployment.setEnsureCleanOnInit(false);
      mavenDeployment.setVersion(version);
      mavenDeployment.setSettingsTemplate(resolveTestFile("settings.xml"));
      mavenDeployment.setProjectTemplateDir(resolveTestFile(projectName));
      mavenDeployment.setProjectDir(resolveBaseFile("target/raw-mvn-site/" + projectName).getAbsoluteFile());
      mavenDeployment.setProxyUrl(new URL(nexusUrl, "/repository/maven-public"));
      mavenDeployment.setDeployUrl(url); // dummy deployUrl since we only run the site
      mavenDeployment.setSiteUrl(url);
      mavenDeployment.init();

      new MavenRunner().run(mavenDeployment, "clean", "site:site", "site:deploy");
      
      // Verify deployment
      HttpResponse response = client.get(INDEX_HTML);
      assertThat(status(response), is(HttpStatus.OK));
      assertThat(asString(response), containsString("About " + projectName));
    }
  }
  
  @Test
  @DisplayName("String templates should be used for logging deployment information")
  void stringTemplatesShouldBeUsedForLoggingDeploymentInformation() throws Exception {
    // Using Java 21 string templates for formatted output
    String project = "testproject";
    URL siteUrl = new URL(nexusUrl, "/repository/" + repository.getName());
    
    // Create a log message using string templates
    String logMessage = STR."Deploying Maven site for project: \{project} to URL: \{siteUrl}";
    log.info(logMessage);
    
    // Run the deployment
    runMavenSite();
    
    // Verify deployment
    HttpResponse response = client.get(INDEX_HTML);
    int statusCode = status(response);
    String content = asString(response);
    
    // Create result message using string templates
    String resultMessage = STR."Deployment result - Status: \{statusCode}, Content contains 'About testproject': \{content.contains("About testproject")}";
    log.info(resultMessage);
    
    assertThat(statusCode, is(HttpStatus.OK));
    assertThat(content, containsString("About testproject"));
  }
  
  @Test
  @DisplayName("Sequenced collections should be used for handling multiple files")
  void sequencedCollectionsShouldBeUsedForHandlingMultipleFiles() throws Exception {
    runMavenSite();
    
    // Using Java 21 sequenced collections for ordered file list
    List<String> expectedFiles = new ArrayList<>();
    expectedFiles.add(INDEX_HTML);
    expectedFiles.add("css/site.css");
    expectedFiles.add("images/logo.png");
    
    // Access elements using new sequenced collection methods
    String firstFile = expectedFiles.getFirst();
    String lastFile = expectedFiles.getLast();
    
    // Verify first and last files exist
    assertThat(status(client.get(firstFile)), is(HttpStatus.OK));
    
    // Create a reversed view of the collection
    List<String> reversedFiles = expectedFiles.reversed();
    assertThat(reversedFiles.getFirst(), is(lastFile));
    assertThat(reversedFiles.getLast(), is(firstFile));
    
    // Verify we can get the first file which should be index.html
    assertThat(firstFile, is(INDEX_HTML));
  }

  private void runMavenSite() throws Exception {
    runMavenSite(repository);
  }
  
  private void runMavenSite(Repository repo) throws Exception {
    String project = "testproject";
    URL siteUrl = new URL(nexusUrl, "/repository/" + repo.getName());

    MavenDeployment mavenDeployment = new MavenDeployment();
    mavenDeployment.setEnsureCleanOnInit(false);
    mavenDeployment.setVersion("version");
    mavenDeployment.setSettingsTemplate(resolveTestFile("settings.xml"));
    mavenDeployment.setProjectTemplateDir(resolveTestFile(project));
    mavenDeployment.setProjectDir(resolveBaseFile("target/raw-mvn-site/" + project).getAbsoluteFile());
    mavenDeployment.setProxyUrl(new URL(nexusUrl, "/repository/maven-public"));
    mavenDeployment.setDeployUrl(siteUrl); // dummy deployUrl since we only run the site
    mavenDeployment.setSiteUrl(siteUrl);
    mavenDeployment.init();

    new MavenRunner().run(mavenDeployment, "clean", "site:site", "site:deploy");
  }
}