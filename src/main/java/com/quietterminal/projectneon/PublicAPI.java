package com.quietterminal.projectneon;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;

/**
 * Indicates that a class, interface, method, or field is part of the public API
 * and is guaranteed to remain stable across minor version updates.
 *
 * <p>Elements marked with this annotation will not have breaking changes
 * within the same major version. Deprecated elements will be marked with
 * {@code @Deprecated} for at least one minor version before removal.
 *
 * <p>Elements not marked with this annotation may change or be removed
 * without notice, even in minor versions.
 *
 * @since 1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({TYPE, METHOD, FIELD, CONSTRUCTOR, PACKAGE})
public @interface PublicAPI {
}
