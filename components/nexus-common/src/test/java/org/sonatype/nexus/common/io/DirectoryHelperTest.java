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
package org.sonatype.nexus.common.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Lists;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.sonatype.goodies.testsupport.hamcrest.FileMatchers.exists;
import static org.sonatype.goodies.testsupport.hamcrest.FileMatchers.isDirectory;
import static org.sonatype.goodies.testsupport.hamcrest.FileMatchers.isEmptyDirectory;
import static org.sonatype.goodies.testsupport.hamcrest.FileMatchers.isFile;

/**
 * Tests for {@link DirectoryHelper}.
 */
public class DirectoryHelperTest
    extends TestSupport
{
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final byte[] PAYLOAD = "payload".getBytes(UTF_8);

  private File root;

  private void createDirectoryStructure(final Path r) throws IOException {
    Files.write(r.resolve("file1.txt"), PAYLOAD);
    Files.write(r.resolve("file2.txt"), PAYLOAD);
    final Path dir1 = Files.createDirectories(r.resolve("dir1"));
    Files.write(dir1.resolve("file11.txt"), PAYLOAD);
    Files.write(dir1.resolve("file12.txt"), PAYLOAD);
    final Path dir2 = Files.createDirectories(r.resolve("dir2"));
    Files.write(dir2.resolve("file21.txt"), PAYLOAD);
    Files.write(dir2.resolve("file22.txt"), PAYLOAD);
    Files.write(dir2.resolve("file23.txt"), PAYLOAD);
    final Path dir21 = Files.createDirectories(dir2.resolve("dir21"));
    Files.write(dir21.resolve("file211.txt"), PAYLOAD);
    Files.write(dir21.resolve("file212.txt"), PAYLOAD);
  }

  @Before
  public void prepare() throws IOException {
    root = util.createTempDir();
    createDirectoryStructure(root.toPath());
  }

  @Test
  public void mkdirWorks() throws IOException {
    final File mkdirA = new File(root, "mkdir-a");
    final File mkdirAB = new File(mkdirA, "mkdir-ab");
    final File dir211 = new File(new File(new File(root, "dir2"), "dir21"), "dir211");
    DirectoryHelper.mkdir(mkdirAB.toPath()); // new
    DirectoryHelper.mkdir(mkdirA.toPath()); // existing
    DirectoryHelper.mkdir(dir211.toPath()); // existing structure
    assertThat(mkdirA, isDirectory());
    assertThat(mkdirAB, isDirectory());
    assertThat(dir211, isDirectory());
  }

  @Test
  public void mkdirWithParentWorks() throws IOException {
    final File mkdirA = DirectoryHelper.mkdir(root, "mkdir-parent-a"); // new
    assertThat(mkdirA, isDirectory());

    File file = DirectoryHelper.mkdir(new File(root, "dir2"), "dir21"); // existing
    assertThat(file, isDirectory());
  }

  @Test
  public void symlinkMkdirWorks() throws IOException {
    final Path dir1link = root.toPath().resolve("dir1-link");
    try {
      // not all OSes support symlink creation
      // if symlink creation fails on given OS, just return from this test
      Files.createSymbolicLink(dir1link, root.toPath().resolve("dir1"));
    }
    catch (IOException e) {
      return;
    }
    DirectoryHelper.mkdir(dir1link);
    assertThat(root.toPath().resolve("dir1-link").toFile().isDirectory(), equalTo(true));
  }

  @Test
  public void cleanWorks() throws IOException {
    DirectoryHelper.clean(root.toPath());
    assertThat(root, exists());
    assertThat(root, isDirectory());
    assertThat(root, not(isEmptyDirectory()));
    assertThat(root.toPath().resolve("dir2").resolve("dir21").toFile(), isDirectory());
  }

  @Test
  public void cleanIfExistsWorks() throws IOException {
    assertThat(DirectoryHelper.cleanIfExists(root.toPath().resolve("not-existing")), is(false));
    assertThat(DirectoryHelper.cleanIfExists(root.toPath()), is(true));
    assertThat(root, exists());
    assertThat(root, isDirectory());
    assertThat(root, not(isEmptyDirectory()));
    assertThat(root.toPath().resolve("dir2").resolve("dir21").toFile(), isDirectory());
  }

  @Test
  public void emptyWorks() throws IOException {
    DirectoryHelper.empty(root.toPath());
    assertThat(root, exists());
    assertThat(root, isDirectory());
    assertThat(root, isEmptyDirectory());
  }

  @Test
  public void emptyIfExistsWorks() throws IOException {
    assertThat(DirectoryHelper.emptyIfExists(root.toPath().resolve("not-existing")), is(false));
    assertThat(DirectoryHelper.emptyIfExists(root.toPath()), is(true));
    assertThat(root, exists());
    assertThat(root, isDirectory());
    assertThat(root, isEmptyDirectory());
  }

  @Test
  public void deleteWorks() throws IOException {
    DirectoryHelper.delete(root.toPath());
    assertThat(root, not(exists()));
  }

  @Test
  public void deleteIfExistsWorks() throws IOException {
    assertThat(DirectoryHelper.deleteIfExists(root.toPath().resolve("not-existing")), is(false));
    assertThat(DirectoryHelper.deleteIfExists(root.toPath()), is(true));
    assertThat(root, not(exists()));
  }

  @Test
  public void copyWorks() throws IOException {
    final Path target = util.createTempDir().toPath();
    DirectoryHelper.copy(root.toPath(), target);
    assertThat(target.toFile(), exists());
    assertThat(target.toFile(), isDirectory());
    assertThat(target.toFile(), not(isEmptyDirectory()));
    assertThat(target.resolve("dir2").resolve("dir21").toFile(), isDirectory());
    assertThat(target.resolve("dir2").resolve("dir21").resolve("file211.txt").toFile(), isFile());
  }

  @Test
  public void copyIfExistsWorks() throws IOException {
    final Path target = util.createTempDir().toPath();
    assertThat(DirectoryHelper.copyIfExists(root.toPath().resolve("not-existing"), target), is(false));
    assertThat(DirectoryHelper.copyIfExists(root.toPath(), target), is(true));
    assertThat(target.toFile(), exists());
    assertThat(target.toFile(), isDirectory());
    assertThat(target.toFile(), not(isEmptyDirectory()));
    assertThat(target.resolve("dir2").resolve("dir21").toFile(), isDirectory());
    assertThat(target.resolve("dir2").resolve("dir21").resolve("file211.txt").toFile(), isFile());
  }

  @Test
  public void moveWorks() throws IOException {
    final Path target = util.createTempDir().toPath();
    DirectoryHelper.move(root.toPath(), target);
    assertThat(root, not(exists()));
    assertThat(target.toFile(), exists());
    assertThat(target.toFile(), isDirectory());
    assertThat(target.toFile(), not(isEmptyDirectory()));
    assertThat(target.resolve("dir2").resolve("dir21").toFile(), isDirectory());
    assertThat(target.resolve("dir2").resolve("dir21").resolve("file211.txt").toFile(), isFile());
  }

  @Test
  public void copyDeleteMoveToSubdirWorks() throws IOException {
    final Path target = root.toPath().resolve("dir2/dir21");
    DirectoryHelper.copyDeleteMove(root.toPath(), target, new Predicate<Path>()
    {
      @Override
      public boolean apply(@Nullable final Path input) {
        return input.startsWith(target);
      }
    });
    assertThat(root, exists());
    assertThat(root.toPath().resolve("dir1").toFile(), not(exists()));
    assertThat(root.toPath().resolve("dir2").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/file21.txt").toFile(), not(exists()));
    assertThat(root.toPath().resolve("dir2/file22.txt").toFile(), not(exists()));
    assertThat(root.toPath().resolve("dir2/file23.txt").toFile(), not(exists()));
    assertThat(root.toPath().resolve("dir2/dir21").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/file211.txt").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/file212.txt").toFile(), exists());

    assertThat(root.toPath().resolve("dir2/dir21/dir1").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/dir1/file11.txt").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/dir1/file12.txt").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/dir2").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/dir2/file21.txt").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/dir2/file22.txt").toFile(), exists());
    assertThat(root.toPath().resolve("dir2/dir21/dir2/file23.txt").toFile(), exists());

    assertThat(root, exists());
    assertThat(target.toFile(), exists());
    assertThat(target.toFile(), isDirectory());
    assertThat(target.toFile(), not(isEmptyDirectory()));
    assertThat(root.toPath().resolve("dir2").resolve("dir21").toFile(), isDirectory());
    assertThat(root.toPath().resolve("dir2").resolve("dir21").resolve("file211.txt").toFile(), isFile());
  }

  /**
   * This is what happened when repo root was being deleted: endless cycle in as "manual" copy/move was
   * performed (during copy), as it copied files "ahead" of itself, basically "rolling" files deeper
   * and deeper. {@link FileSystemException} is thrown once file path length reaches OS limit. In case
   * of repo local storage, the root was being moved under "/.nexus/trash".
   */
  @Test(expected = FileSystemException.class)
  public void moveToSubdirThrowsException() throws IOException {
    final Path target = root.toPath().resolve("dir2/dir21");
    DirectoryHelper.move(root.toPath(), target);
  }

  @Test(expected = IllegalArgumentException.class)
  public void copyingToChildDirDisallowedWithoutFilter() throws IOException {
    final Path target = root.toPath().resolve("dir2/dir21");
    DirectoryHelper.copyDeleteMove(root.toPath(), target, null);
  }

  @Test
  public void moveIfExistsWorks() throws IOException {
    final Path target = util.createTempDir().toPath();
    assertThat(DirectoryHelper.moveIfExists(root.toPath().resolve("not-existing"), target), is(false));
    assertThat(DirectoryHelper.moveIfExists(root.toPath(), target), is(true));
    assertThat(root, not(exists()));
    assertThat(target.toFile(), exists());
    assertThat(target.toFile(), isDirectory());
    assertThat(target.toFile(), not(isEmptyDirectory()));
    assertThat(target.resolve("dir2").resolve("dir21").toFile(), isDirectory());
    assertThat(target.resolve("dir2").resolve("dir21").resolve("file211.txt").toFile(), isFile());
  }

  @Test
  public void applyWorks() throws IOException {
    final ArrayList<String> fileNames = Lists.newArrayList();
    final ArrayList<String> dirNames = Lists.newArrayList();
    final Function<Path, FileVisitResult> tf = new Function<Path, FileVisitResult>()
    {
      @Override
      public FileVisitResult apply(final Path input) {
        if (Files.isDirectory(input)) {
          dirNames.add(input.getFileName().toString());
        }
        else if (Files.isRegularFile(input)) {
          fileNames.add(input.getFileName().toString());
        }
        return FileVisitResult.CONTINUE;
      }
    };
    DirectoryHelper.apply(root.toPath(), tf);

    assertThat(fileNames, hasSize(9));
    // root + 3dirs
    assertThat(dirNames, hasSize(4));
  }

  @Test
  public void applyToFilesWorks() throws IOException {
    final ArrayList<String> fileNames = Lists.newArrayList();
    final ArrayList<String> dirNames = Lists.newArrayList();
    final Function<Path, FileVisitResult> tf = new Function<Path, FileVisitResult>()
    {
      @Override
      public FileVisitResult apply(final Path input) {
        if (Files.isDirectory(input)) {
          dirNames.add(input.getFileName().toString());
        }
        else if (Files.isRegularFile(input)) {
          fileNames.add(input.getFileName().toString());
        }
        return FileVisitResult.CONTINUE;
      }
    };
    DirectoryHelper.applyToFiles(root.toPath(), tf);

    assertThat(fileNames, hasSize(9));
    // func never invoked on dirs
    assertThat(dirNames, hasSize(0));
  }

  @Test
  public void deleteIfEmptyRecursivelyWorks() throws Exception {
    File dir = temporaryFolder.newFolder("basedir");

    // now lets start adding some directories
    // first off a simple empty directory
    File subdir = new File(dir, "sub");
    Files.createDirectory(subdir.toPath());

    // now some nested empty directories
    subdir = new File(dir, "subnested");
    Files.createDirectory(subdir.toPath());
    for (int i = 0; i < 10; i++) {
      subdir = new File(subdir, "subnested" + i);
      Files.createDirectory(subdir.toPath());
    }

    // now a directory with a file in it
    subdir = new File(dir, "subwithcontent");
    Files.createDirectory(subdir.toPath());
    new File(subdir, "afile.txt").createNewFile();

    // now a nested directory with a file in it
    subdir = new File(dir, "subnestedwithcontent");
    Files.createDirectory(subdir.toPath());
    for (int i = 0; i < 10; i++) {
      subdir = new File(subdir, "subnestedwithcontent" + i);
      Path newdir = Files.createDirectory(subdir.toPath());
      if (i == 9) {
        new File(newdir.toFile(), "afile.txt").createNewFile();
      }
    }

    int count = DirectoryHelper.deleteIfEmptyRecursively(dir.toPath(), null);

    assertThat(dir, exists());
    assertThat(new File(dir, "sub"), not(exists()));
    assertThat(new File(dir, "subnested"), not(exists()));
    assertThat(new File(dir, "subwithcontent"), exists());
    assertThat(new File(dir, "subnestedwithcontent"), exists());
    assertThat(count, is(12));
  }

  @Test
  public void deleteIfEmptyRecursivelyWithMissingDirectoryWorks() throws Exception {
    int count = DirectoryHelper.deleteIfEmptyRecursively(Paths.get("fake", "dir"), null);
    assertThat(count, is(0));
  }

  @Test
  public void deleteIfEmptyRecursivelySkipNewerDirsWorks() throws Exception {
    File dir = temporaryFolder.newFolder("basedir");

    // This directory will be the one that is slightly older than the timestamp so _should_ get deleted
    File subdir = new File(dir, "sub");
    Files.createDirectory(subdir.toPath());

    // put some sleeps around the timestamp, to guaranty state, and that the timestamp wont errantly associate with the
    // test created directories
    Thread.sleep(1000);
    Date okTimestamp = new Date();
    Thread.sleep(1000);

    // This directory should come after the timestamp, so should not get deleted
    subdir = new File(dir, "sub2");
    Files.createDirectory(subdir.toPath());

    int count = DirectoryHelper.deleteIfEmptyRecursively(dir.toPath(), okTimestamp.getTime());

    assertThat(count, is(1));
    assertThat(new File(dir, "sub"), not(exists()));
    assertThat(new File(dir, "sub2"), exists());
  }
  
  /**
   * Tests directory operations with Virtual Threads to ensure they work correctly
   * in a concurrent environment with the new Java 21 threading model.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void parallelDirectoryOperationsWithVirtualThreadsWork() throws Exception {
    // Skip test if Virtual Threads are not supported
    VirtualThreadTestSupport.assumeVirtualThreadSupported();
    
    // Create a temporary directory for testing
    File testDir = temporaryFolder.newFolder("virtual-thread-test");
    Path testPath = testDir.toPath();
    
    // Number of operations to perform concurrently
    int operationCount = 50;
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("dir-op-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // CountDownLatch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(operationCount);
      
      // Track any exceptions that occur during execution
      AtomicReference<Exception> firstException = new AtomicReference<>();
      
      // Perform concurrent directory operations
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique subdirectory for each thread
            Path subDir = testPath.resolve("subdir-" + index);
            DirectoryHelper.mkdir(subDir);
            
            // Create some files in the subdirectory
            Files.write(subDir.resolve("file1.txt"), ("content-" + index).getBytes(UTF_8));
            Files.write(subDir.resolve("file2.txt"), ("content-" + index).getBytes(UTF_8));
            
            // Create a nested directory
            Path nestedDir = subDir.resolve("nested");
            DirectoryHelper.mkdir(nestedDir);
            Files.write(nestedDir.resolve("nested-file.txt"), ("nested-content-" + index).getBytes(UTF_8));
            
            // Perform a copy operation
            Path copyTarget = testPath.resolve("copy-" + index);
            DirectoryHelper.copy(subDir, copyTarget);
            
            // Verify the copy worked
            assertThat(Files.exists(copyTarget.resolve("file1.txt")), is(true));
            assertThat(Files.exists(copyTarget.resolve("nested/nested-file.txt")), is(true));
            
            // Clean the copied directory (removes files but keeps directories)
            DirectoryHelper.clean(copyTarget);
            assertThat(Files.exists(copyTarget), is(true));
            assertThat(Files.exists(copyTarget.resolve("file1.txt")), is(false));
            assertThat(Files.exists(copyTarget.resolve("nested")), is(true));
            
            // Empty the original directory (removes all content including directories)
            DirectoryHelper.empty(subDir);
            assertThat(Files.exists(subDir), is(true));
            assertThat(Files.exists(subDir.resolve("nested")), is(false));
            
            // Delete the empty directories
            DirectoryHelper.delete(subDir);
            DirectoryHelper.delete(copyTarget);
            assertThat(Files.exists(subDir), is(false));
            assertThat(Files.exists(copyTarget), is(false));
          }
          catch (Exception e) {
            firstException.compareAndSet(null, e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete (with timeout)
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All directory operations should complete within the timeout", completed, is(true));
      
      // Check if any exceptions occurred
      Exception exception = firstException.get();
      if (exception != null) {
        throw exception;
      }
    }
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests for thread pinning when performing directory operations with Virtual Threads.
   * Thread pinning can occur when using synchronized blocks or native methods, which can
   * negatively impact performance with Virtual Threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void directoryOperationsDoNotCauseThreadPinning() throws Exception {
    // Skip test if Virtual Threads are not supported
    VirtualThreadTestSupport.assumeVirtualThreadSupported();
    
    // Create a temporary directory for testing
    File testDir = temporaryFolder.newFolder("pinning-test");
    Path testPath = testDir.toPath();
    
    // Test mkdir operation for thread pinning
    boolean mkdirPinning = VirtualThreadTestSupport.detectThreadPinning(() -> {
      try {
        Path subDir = testPath.resolve("pinning-subdir");
        DirectoryHelper.mkdir(subDir);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    
    // Test copy operation for thread pinning
    boolean copyPinning = VirtualThreadTestSupport.detectThreadPinning(() -> {
      try {
        Path sourceDir = testPath.resolve("pinning-source");
        Path targetDir = testPath.resolve("pinning-target");
        DirectoryHelper.mkdir(sourceDir);
        Files.write(sourceDir.resolve("test.txt"), "test content".getBytes(UTF_8));
        DirectoryHelper.copy(sourceDir, targetDir);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    
    // Test delete operation for thread pinning
    boolean deletePinning = VirtualThreadTestSupport.detectThreadPinning(() -> {
      try {
        Path deleteDir = testPath.resolve("pinning-delete");
        DirectoryHelper.mkdir(deleteDir);
        Files.write(deleteDir.resolve("test.txt"), "test content".getBytes(UTF_8));
        DirectoryHelper.delete(deleteDir);
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    
    // Assert that no thread pinning was detected in any operation
    assertThat("mkdir operation should not cause thread pinning", mkdirPinning, is(false));
    assertThat("copy operation should not cause thread pinning", copyPinning, is(false));
    assertThat("delete operation should not cause thread pinning", deletePinning, is(false));
  }
  
  /**
   * Measures and compares the performance of directory operations when executed with
   * Virtual Threads versus Platform Threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void directoryOperationsPerformanceWithVirtualThreads() throws Exception {
    // Skip test if Virtual Threads are not supported
    VirtualThreadTestSupport.assumeVirtualThreadSupported();
    
    // Create a temporary directory for testing
    File testDir = temporaryFolder.newFolder("performance-test");
    Path testPath = testDir.toPath();
    
    // Number of operations to perform concurrently
    int operationCount = 100;
    
    // Create thread factories for both types of threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("vt-dir-op-", 0).factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().name("pt-dir-op-", 0).factory();
    
    // Measure execution time with platform threads
    long platformThreadTime = measureExecutionTime(testPath, operationCount, platformThreadFactory);
    
    // Measure execution time with virtual threads
    long virtualThreadTime = measureExecutionTime(testPath, operationCount, virtualThreadFactory);
    
    // Log the performance results
    log.info("Directory operations performance comparison:");
    log.info("Platform Threads: {} ms for {} operations", platformThreadTime, operationCount);
    log.info("Virtual Threads: {} ms for {} operations", virtualThreadTime, operationCount);
    log.info("Performance improvement: {}%", 
        platformThreadTime > 0 ? (platformThreadTime - virtualThreadTime) * 100 / platformThreadTime : "N/A");
    
    // For high concurrency operations, virtual threads should generally perform better
    // This assertion might need adjustment based on the specific environment
    assertThat("Virtual threads should perform better for concurrent I/O operations", 
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Helper method to measure execution time of concurrent directory operations using the specified thread factory.
   */
  private long measureExecutionTime(Path basePath, int operationCount, ThreadFactory threadFactory) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Create a unique test directory for this run
      Path testPath = basePath.resolve("perf-" + System.currentTimeMillis());
      DirectoryHelper.mkdir(testPath);
      
      // CountDownLatch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(operationCount);
      
      // Track any exceptions that occur during execution
      List<Exception> exceptions = new ArrayList<>();
      
      // Start timing
      long startTime = System.currentTimeMillis();
      
      // Perform concurrent directory operations
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique subdirectory for each thread
            Path subDir = testPath.resolve("subdir-" + index);
            DirectoryHelper.mkdir(subDir);
            
            // Create some files in the subdirectory
            Files.write(subDir.resolve("file1.txt"), ("content-" + index).getBytes(UTF_8));
            Files.write(subDir.resolve("file2.txt"), ("content-" + index).getBytes(UTF_8));
            
            // Create a nested directory
            Path nestedDir = subDir.resolve("nested");
            DirectoryHelper.mkdir(nestedDir);
            Files.write(nestedDir.resolve("nested-file.txt"), ("nested-content-" + index).getBytes(UTF_8));
            
            // Perform a copy operation
            Path copyTarget = testPath.resolve("copy-" + index);
            DirectoryHelper.copy(subDir, copyTarget);
            
            // Clean the copied directory
            DirectoryHelper.clean(copyTarget);
            
            // Empty the original directory
            DirectoryHelper.empty(subDir);
            
            // Delete the empty directories
            DirectoryHelper.delete(subDir);
            DirectoryHelper.delete(copyTarget);
          }
          catch (Exception e) {
            synchronized (exceptions) {
              exceptions.add(e);
            }
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      latch.await();
      
      // End timing
      long endTime = System.currentTimeMillis();
      
      // Check if any exceptions occurred
      if (!exceptions.isEmpty()) {
        throw exceptions.get(0);
      }
      
      // Clean up the test directory
      DirectoryHelper.delete(testPath);
      
      return endTime - startTime;
    }
    finally {
      executor.shutdown();
    }
  }
}