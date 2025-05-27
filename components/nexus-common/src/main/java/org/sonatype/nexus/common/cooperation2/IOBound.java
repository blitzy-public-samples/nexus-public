package org.sonatype.nexus.common.cooperation2;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark operations that are I/O-bound and suitable for execution on Virtual Threads.
 * 
 * @since 3.41
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface IOBound {
  /**
   * Optional description of the I/O operation being performed.
   */
  String value() default "";
}