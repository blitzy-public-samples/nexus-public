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
package org.sonatype.nexus.pax;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.pax.exam.NexusPaxExamSupport;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.ExpectedException;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.MavenUrlReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.ops4j.pax.exam.CoreOptions.cleanCaches;
import static org.ops4j.pax.exam.CoreOptions.composite;
import static org.ops4j.pax.exam.CoreOptions.maven;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.CoreOptions.systemTimeout;
import static org.ops4j.pax.exam.CoreOptions.vmOption;
import static org.ops4j.pax.exam.CoreOptions.when;
import static org.ops4j.pax.exam.CoreOptions.wrappedBundle;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.configureConsole;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.editConfigurationFilePut;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.features;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.karafDistributionConfiguration;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.keepRuntimeFolder;
import static org.ops4j.pax.exam.karaf.options.KarafDistributionOption.logLevel;
import static org.ops4j.pax.exam.karaf.options.LogLevelOption.LogLevel.INFO;

/**
 * Provides support for testing Nexus with Pax-Exam, test-cases can inject any component from the distribution.
 *
 * @since 3.0
 */
public abstract class NexusPaxExamSupport
{
  protected static final Logger log = LoggerFactory.getLogger(NexusPaxExamSupport.class);

  /**
   * Key identifying a system property which if set, should be used as a prefix for {@code it-data} subdirectory
   * names to prevent a collision when Jenkins aggregates across parallel stages.
   * (Jenkins isn't smart enough to keep the test data separate when it aggregates test results.)
   */
  private static final String IT_DATA_PREFIX_KEY = "it.data.prefix";

  /**
   * Key identifying a system property which if set to {@code true}, enables virtual threads for tests.
   */
  private static final String TEST_VIRTUAL_THREADS_KEY = "test.virtual.threads";

  /**
   * Default value for the test.virtual.threads system property.
   */
  private static final String TEST_VIRTUAL_THREADS_DEFAULT = "false";

  /**
   * Shared test index to help track what's being tested.
   */
  @Rule
  @Inject
  public TestIndexRule testIndex;

  /**
   * Automatically unregister any test resources.
   */
  @Rule
  public TestCleaner testCleaner = new TestCleaner();

  /**
   * Allow tests to customize expected exception details.
   */
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  /**
   * Extend this to configure the test container.
   */
  protected Option[] configureNexus() {
    return options(nexusDistribution());
  }

  /**
   * Override this to customize the test container.
   */
  protected Option[] configureNexus(final MavenUrlReference nexusDistribution) {
    return options(nexusDistribution(nexusDistribution));
  }

  /**
   * Extend this to configure the Java VM.
   */
  protected Option javaVMOption() {
    return javaVMCompositeOption();
  }

  /**
   * Composite of Java VM options.
   */
  protected Option javaVMCompositeOption() {
    String version = System.getProperty("java.version", "");
    if (version.startsWith("1.8")) {
      return composite(
          // no options for Java 8
      );
    }
    else if (version.startsWith("11")) {
      return composite(
          // Java 11 support
          vmOption("--add-exports=java.base/org.apache.karaf.specs.locator=java.xml,ALL-UNNAMED"),
          vmOption("--patch-module"),
          vmOption("java.base=lib/endorsed/org.apache.karaf.specs.locator-4.3.9.jar"),
          vmOption("--patch-module"),
          vmOption("java.xml=lib/endorsed/org.apache.karaf.specs.java.xml-4.3.9.jar"),
          vmOption("--add-opens"),
          vmOption("java.base/java.security=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.base/java.net=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.base/java.lang=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.base/java.util=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.naming/javax.naming.spi=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.rmi/sun.rmi.transport.tcp=ALL-UNNAMED"),
          vmOption("--add-exports=java.base/sun.net.www.protocol.http=ALL-UNNAMED"),
          vmOption("--add-exports=java.base/sun.net.www.protocol.https=ALL-UNNAMED"),
          vmOption("--add-exports=java.base/sun.net.www.protocol.jar=ALL-UNNAMED"),
          vmOption("--add-exports=jdk.naming.rmi/com.sun.jndi.url.rmi=ALL-UNNAMED"),
          vmOption("--add-exports=java.rmi/sun.rmi.registry=ALL-UNNAMED"),
          vmOption("-classpath"),
          vmOption("lib/jdk9plus/*:lib/boot/*:lib/endorsed/*")
      );
    }
    else if (version.startsWith("17")) {
      return composite(
          // Java 17 support
          vmOption("--add-exports=java.base/org.apache.karaf.specs.locator=java.xml,ALL-UNNAMED"),
          vmOption("--patch-module"),
          vmOption("java.base=lib/endorsed/org.apache.karaf.specs.locator-4.3.9.jar"),
          vmOption("--patch-module"),
          vmOption("java.xml=lib/endorsed/org.apache.karaf.specs.java.xml-4.3.9.jar"),
          vmOption("--add-opens"),
          vmOption("java.base/java.security=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.base/java.net=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.base/java.lang=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.base/java.util=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.naming/javax.naming.spi=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.rmi/sun.rmi.transport.tcp=ALL-UNNAMED"),
          vmOption("--add-exports=java.base/sun.net.www.protocol.http=ALL-UNNAMED"),
          vmOption("--add-exports=java.base/sun.net.www.protocol.https=ALL-UNNAMED"),
          vmOption("--add-exports=java.base/sun.net.www.protocol.jar=ALL-UNNAMED"),
          vmOption("--add-exports=jdk.naming.rmi/com.sun.jndi.url.rmi=ALL-UNNAMED"),
          vmOption("--add-exports=java.rmi/sun.rmi.registry=ALL-UNNAMED"),
          vmOption("-classpath"),
          vmOption("lib/jdk9plus/*:lib/boot/*:lib/endorsed/*")
      );
    }
    else if (version.startsWith("21")) {
      return composite(
          // Java 21 support
          vmOption("--enable-preview"),
          vmOption("--add-exports=java.base/org.apache.karaf.specs.locator=java.xml,ALL-UNNAMED"),
          vmOption("--patch-module"),
          vmOption("java.base=lib/endorsed/org.apache.karaf.specs.locator-4.3.9.jar"),
          vmOption("--patch-module"),
          vmOption("java.xml=lib/endorsed/org.apache.karaf.specs.java.xml-4.3.9.jar"),
          vmOption("--add-opens"),
          vmOption("java.base/java.security=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.base/java.net=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.base/java.lang=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.base/java.util=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.naming/javax.naming.spi=ALL-UNNAMED"),
          vmOption("--add-opens"),
          vmOption("java.rmi/sun.rmi.transport.tcp=ALL-UNNAMED"),
          vmOption("--add-exports=java.base/sun.net.www.protocol.http=ALL-UNNAMED"),
          vmOption("--add-exports=java.base/sun.net.www.protocol.https=ALL-UNNAMED"),
          vmOption("--add-exports=java.base/sun.net.www.protocol.jar=ALL-UNNAMED"),
          vmOption("--add-exports=jdk.naming.rmi/com.sun.jndi.url.rmi=ALL-UNNAMED"),
          vmOption("--add-exports=java.rmi/sun.rmi.registry=ALL-UNNAMED"),
          vmOption("--add-exports=jdk.xml.dom/org.w3c.dom.html=ALL-UNNAMED"),
          vmOption("--add-exports=java.security.sasl/com.sun.security.sasl=ALL-UNNAMED"),
          // ZGC GC flags
          vmOption("-XX:+UseZGC"),
          vmOption("-XX:+ZGenerational"),
          // Virtual Thread debugging and scheduler configuration
          vmOption("-Djdk.tracePinnedThreads=full"),
          vmOption("-Djdk.virtualThreadScheduler.parallelism=16"),
          vmOption("-Djdk.virtualThreadScheduler.maxPoolSize=256"),
          // Propagate test.virtual.threads system property
          vmOption("-Dtest.virtual.threads=" + System.getProperty(TEST_VIRTUAL_THREADS_KEY, TEST_VIRTUAL_THREADS_DEFAULT)),
          vmOption("-classpath"),
          vmOption("lib/jdk9plus/*:lib/boot/*:lib/endorsed/*")
      );
    }
    else {
      throw new IllegalStateException("Unsupported Java version: " + version);
    }
  }

  /**
   * Combine the given options with common test options.
   */
  protected Option[] options(final Option... options) {
    List<Option> result = new ArrayList<>();
    result.addAll(Arrays.asList(options));

    result.add(systemTimeout(TimeUnit.MINUTES.toMillis(10)));
    result.add(cleanCaches(false));
    result.add(javaVMOption());

    return result.toArray(new Option[result.size()]);
  }

  /**
   * @return Nexus distribution to test
   */
  protected MavenUrlReference nexusDistribution() {
    return maven("org.sonatype.nexus.assemblies", "nexus-base-template")
        .version(nexusVersion())
        .type("zip");
  }

  /**
   * @return Version of Nexus distribution to test
   */
  protected String nexusVersion() {
    return System.getProperty("it.nexus.bundle.version");
  }

  /**
   * @return Common options for Nexus distributions
   */
  protected Option[] nexusDistribution(final MavenUrlReference nexusDistribution) {
    return new Option[]{
        karafDistributionConfiguration()
            .frameworkUrl(nexusDistribution)
            .unpackDirectory(testData().resolveFile("unpacked-nexus"))
            .useDeployFolder(false),

        configureConsole().ignoreLocalConsole(),

        when(Boolean.getBoolean("it.keepRuntimeFolder")).useOptions(keepRuntimeFolder()),

        logLevel(INFO),

        editConfigurationFilePut("etc/nexus-default.properties", "nexus-context-path", "/")
    };
  }

  /**
   * @return Common options for Nexus plugins
   */
  protected Option[] nexusPlugins(final String... plugins) {
    List<Option> result = new ArrayList<>();
    for (String plugin : plugins) {
      result.add(mavenBundle("org.sonatype.nexus.plugins", plugin).version(nexusVersion()));
    }
    return result.toArray(new Option[result.size()]);
  }

  /**
   * @return Common options for Nexus features
   */
  protected Option[] nexusFeatures(final String... features) {
    return new Option[]{
        features(maven("org.sonatype.nexus.assemblies", "nexus-base-feature")
            .version(nexusVersion())
            .classifier("features")
            .type("xml"),
            features)
    };
  }

  /**
   * @return Common options for Nexus plugins
   */
  protected Option[] spiFlyFeature() {
    return new Option[]{
        mavenBundle("org.apache.aries.spifly", "org.apache.aries.spifly.dynamic.bundle").version("1.3.6"),
        wrappedBundle(mavenBundle("org.ow2.asm", "asm").version("9.4")),
        wrappedBundle(mavenBundle("org.ow2.asm", "asm-commons").version("9.4")),
        wrappedBundle(mavenBundle("org.ow2.asm", "asm-util").version("9.4")),
        wrappedBundle(mavenBundle("org.ow2.asm", "asm-tree").version("9.4")),
        wrappedBundle(mavenBundle("org.ow2.asm", "asm-analysis").version("9.4")),
        wrappedBundle(mavenBundle("org.apache.aries", "org.apache.aries.util").version("1.1.3"))
    };
  }

  /**
   * @return TestData directory for current test-class
   */
  protected TestData testData() {
    return new TestData(getClass());
  }

  /**
   * @return Resolves path against the test-data directory for the current test-class
   */
  protected File resolveTestFile(final String path) {
    return testData().resolveFile(path);
  }

  /**
   * @return Resolves path against the test-data directory for the current test-class
   */
  protected URL resolveTestResource(final String path) throws IOException {
    return testData().resolveFile(path).toURI().toURL();
  }

  /**
   * @return Resolves path against the test-index directory for the current test-class
   */
  protected File resolveIndexFile(final String path) {
    return testIndex.resolveFile(path);
  }

  /**
   * Periodically polls condition until it returns {@code true} or the given timeout is reached.
   *
   * @return {@code true} if condition returned true before the timeout was reached
   */
  protected boolean waitFor(final Condition condition, final long timeout, final TimeUnit unit) {
    final long deadline = System.nanoTime() + unit.toNanos(timeout);
    try {
      long delay = 10;
      while (System.nanoTime() < deadline) {
        if (condition.call()) {
          return true;
        }
        Thread.sleep(Math.min(delay, MILLISECONDS.convert(deadline - System.nanoTime(), SECONDS)));
        delay = Math.min(delay * 2, 200);
      }
    }
    catch (final Exception e) {
      throw new RuntimeException(e);
    }
    return false;
  }

  /**
   * Periodically polls condition until it returns {@code true} or the timeout of 30 seconds is reached.
   *
   * @return {@code true} if condition returned true before the timeout was reached
   */
  protected boolean waitFor(final Condition condition) {
    return waitFor(condition, 30, SECONDS);
  }

  /**
   * Captures test execution details to help track what's being tested.
   */
  @Before
  public void captureLogs() {
    testIndex.recordAndCopyLink(resolveTestFile("karaf.log"), "karaf.log");
  }

  /**
   * Dumps various test execution details in case of a test failure.
   */
  @After
  public void dumpLogs() {
    if (testIndex.getFailure() != null) {
      log.info("Dumping test logs due to test failure");
      dumpFileIfExists(resolveTestFile("karaf.log"));
    }
  }

  /**
   * Dumps the content of the given file if it exists..
   */
  private static void dumpFileIfExists(final File file) {
    if (file.exists()) {
      log.info("BEGIN: {}", file);
      try {
        // not using Files.readAllBytes as that has charset conversion issues with karaf.log
        final byte[] buf = new byte[1024];
        try (final java.io.FileInputStream in = new java.io.FileInputStream(file)) {
          int len;
          while ((len = in.read(buf)) != -1) {
            System.out.write(buf, 0, len);
          }
        }
      }
      catch (final IOException e) {
        log.warn("Problem dumping file: {}", file, e);
      }
      log.info("END: {}", file);
    }
  }

  /**
   * Cleans up test resources; this complements the {@link TestCleaner} rule.
   */
  @AfterClass
  public static void cleanupClass() {
    TestData.cleanupClass();
  }

  /**
   * Something that can be tested for a condition.
   */
  @FunctionalInterface
  protected interface Condition
  {
    boolean call() throws Exception;
  }

  /**
   * Provides access to test-data resources for a specific test.
   */
  protected static class TestData
  {
    private static final File TARGET_DIR = new File(System.getProperty("basedir", "."), "target");

    private static final File IT_DIR = new File(TARGET_DIR, "it-data");

    private static final File IT_RESULTS_DIR = new File(TARGET_DIR, "it-results");

    private static final String IT_DATA_PREFIX = System.getProperty(IT_DATA_PREFIX_KEY, "");

    private final File classDir;

    /**
     * Creates a new test-data accessor for the given test class.
     */
    public TestData(final Class<?> clazz) {
      String className = clazz.getName();
      if (IT_DATA_PREFIX.length() > 0) {
        className = IT_DATA_PREFIX + '.' + className;
      }
      classDir = new File(IT_DIR, className);
      classDir.mkdirs();
    }

    /**
     * Resolves the given path against the test-data directory for this test.
     */
    public File resolveFile(final String path) {
      return new File(classDir, path);
    }

    /**
     * Cleans up test-data directories that no longer have matching test classes.
     */
    public static void cleanupClass() {
      if (IT_DIR.exists()) {
        // only attempt cleanup if directory exists
        final String[] testClasses = IT_DIR.list();
        if (testClasses != null) {
          for (final String className : testClasses) {
            String testClassName = className;
            if (IT_DATA_PREFIX.length() > 0 && className.startsWith(IT_DATA_PREFIX + '.')) {
              testClassName = className.substring(IT_DATA_PREFIX.length() + 1);
            }
            try {
              // is this a valid class?
              Class.forName(testClassName);
            }
            catch (final Exception | LinkageError e) {
              // if not then delete its test data
              deleteQuietly(new File(IT_DIR, className));
            }
          }
        }
      }
    }
  }

  /**
   * Provides access to test-index resources for a specific test.
   */
  @Named
  @Singleton
  protected static class TestIndexRule
      extends org.sonatype.nexus.pax.exam.NexusPaxExamTestIndexRule
  {
    // this class exists to make the rule available to the OSGi container
  }

  /**
   * Deletes the given file or directory without reporting any errors.
   */
  private static boolean deleteQuietly(final File file) {
    if (file != null) {
      try {
        if (file.isDirectory()) {
          final File[] files = file.listFiles();
          if (files != null) {
            for (final File f : files) {
              deleteQuietly(f);
            }
          }
        }
        return file.delete();
      }
      catch (final Exception e) {
        return false;
      }
    }
    return false;
  }
}